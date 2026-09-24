package br.com.mncheck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class InventoryCountingService {
  private static final Set<String> CATEGORIES = Set.of("BOA", "AVARIA", "ASSISTENCIA", "OUTROS");
  private static final Set<String> ACTION_TYPES = Set.of("DEFINIR", "SOMAR", "CORRECAO");
  private static final Set<String> ORIGINS = Set.of("SCANNER", "MANUAL", "OFFLINE");

  private final DatabaseUrlParser.JdbcConfig database;

  public InventoryCountingService() {
    this(System.getenv("DATABASE_URL"));
  }

  InventoryCountingService(String databaseUrl) {
    this.database = DatabaseUrlParser.parse(databaseUrl);
  }

  public RoundDetail getActiveRound(long inventoryId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      requireBranch(connection, normalizedBranch);
      InventoryHeader inventory = requireInventory(connection, inventoryId, normalizedBranch);
      RoundRecord round = findActiveRound(connection, inventoryId);
      if (round == null) {
        throw new NotFoundException("Nenhuma rodada de contagem ativa encontrada para este inventário.");
      }
      CountingProgress progress = calculateProgress(connection, inventoryId, round.id());
      return new RoundDetail(
          round.id(),
          round.inventarioId(),
          round.numero(),
          round.tipo(),
          round.status(),
          round.iniciadaPor(),
          round.iniciadaEm(),
          inventory.nome(),
          inventory.modo(),
          inventory.status(),
          progress
      );
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar a rodada ativa.", error);
    }
  }

  public ItemListResult listItems(long inventoryId, String branchCode, String search, String estadoFilter) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      requireBranch(connection, normalizedBranch);
      InventoryHeader inventory = requireInventory(connection, inventoryId, normalizedBranch);
      RoundRecord round = findActiveRound(connection, inventoryId);
      long activeRoundId = round == null ? 0L : round.id();

      boolean isBlind = "CEGO".equalsIgnoreCase(inventory.modo());
      List<ItemRecord> rawItems = loadInventoryItems(connection, inventoryId, search);

      Map<Long, List<OccurrenceRecord>> occurrencesByItem = activeRoundId > 0
          ? loadRoundOccurrences(connection, activeRoundId)
          : Map.of();

      List<CountingItem> items = new ArrayList<>();
      int countedCount = 0;

      for (ItemRecord item : rawItems) {
        List<OccurrenceRecord> occurrences = occurrencesByItem.getOrDefault(item.id(), List.of());
        boolean hasOccurrences = !occurrences.isEmpty();
        if (hasOccurrences) countedCount++;

        String itemEstado = hasOccurrences ? "CONTADO" : "PENDENTE";

        if (estadoFilter != null && !estadoFilter.isBlank()) {
          String normalizedFilter = estadoFilter.trim().toUpperCase(Locale.ROOT);
          if (!itemEstado.equals(normalizedFilter)) continue;
        }

        ItemProjection projection = calculateItemProjection(occurrences);
        Integer exposedSnapshot = isBlind ? null : item.saldoSnapshot();

        items.add(new CountingItem(
            item.id(),
            item.sku(),
            item.descricao(),
            exposedSnapshot,
            projection.totalQuantity(),
            projection.categoryQuantities(),
            itemEstado,
            projection.lastOccurrenceId(),
            projection.lastOccurrenceTimestamp()
        ));
      }

      int totalItems = rawItems.size();
      int pendingCount = totalItems - countedCount;
      int percent = totalItems == 0 ? 0 : Math.round((countedCount * 100f) / totalItems);
      CountingProgress progress = new CountingProgress(totalItems, countedCount, pendingCount, percent);

      return new ItemListResult(
          inventory.id(),
          inventory.nome(),
          inventory.modo(),
          inventory.status(),
          activeRoundId,
          progress,
          items
      );
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível listar os itens do inventário.", error);
    }
  }

  public OccurrenceResult recordOccurrence(
      long inventoryId,
      long roundId,
      String branchCode,
      RecordOccurrenceCommand command,
      String operator
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String sku = required(command.sku(), "SKU não informado.");
    int quantidade = command.quantidade();
    if (quantidade < 0) throw new ValidationException("Quantidade não pode ser negativa.");

    String categoria = enumValue(command.categoria() == null || command.categoria().isBlank()
        ? "BOA" : command.categoria(), CATEGORIES, "Categoria inválida.");
    String tipoAcao = enumValue(command.tipoAcao() == null || command.tipoAcao().isBlank()
        ? "DEFINIR" : command.tipoAcao(), ACTION_TYPES, "Tipo de ação inválido.");
    String origem = enumValue(command.origem() == null || command.origem().isBlank()
        ? "SCANNER" : command.origem(), ORIGINS, "Origem inválida.");

    UUID clientEventId;
    try {
      clientEventId = command.clientEventId() instanceof UUID u ? u
          : UUID.fromString(command.clientEventId().toString());
    } catch (Exception ex) {
      throw new ValidationException("client_event_id inválido ou ausente. Deve ser um UUID.");
    }

    String safeOperator = required(operator, "Operador autenticado não identificado.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranch(connection, normalizedBranch);
        InventoryHeader inventory = requireInventory(connection, inventoryId, normalizedBranch);
        if (!"EM_CONTAGEM".equalsIgnoreCase(inventory.status())) {
          throw new ConflictException("Inventário não está em contagem (status atual: " + inventory.status() + ").");
        }

        RoundRecord round = requireRound(connection, roundId, inventoryId);
        if (!"EM_ANDAMENTO".equalsIgnoreCase(round.status())) {
          throw new ConflictException("Rodada " + round.numero() + " não está em andamento.");
        }

        ItemRecord item = findItemBySku(connection, inventoryId, sku);
        if (item == null) {
          throw new NotFoundException("SKU " + sku + " não pertence a este inventário.");
        }

        if (command.referenciaId() != null) {
          validateReferenceOccurrence(connection, round.id(), item.id(), command.referenciaId());
        }

        OccurrenceRecord occurrence = insertOccurrenceIdempotent(
            connection,
            round.id(),
            item.id(),
            sku,
            quantidade,
            categoria,
            tipoAcao,
            safeOperator,
            clientEventId,
            origem,
            command.dispositivo(),
            command.clientTimestamp(),
            command.referenciaId()
        );

        CountingProgress progress = calculateProgress(connection, inventoryId, round.id());
        List<OccurrenceRecord> allItemOccurrences = loadItemOccurrences(connection, round.id(), item.id());
        ItemProjection projection = calculateItemProjection(allItemOccurrences);

        connection.commit();

        boolean isBlind = "CEGO".equalsIgnoreCase(inventory.modo());
        return new OccurrenceResult(
            occurrence.id(),
            occurrence.rodadaId(),
            occurrence.inventarioItemId(),
            occurrence.sku(),
            occurrence.quantidade(),
            occurrence.categoria(),
            occurrence.tipoAcao(),
            occurrence.operador(),
            occurrence.clientEventId(),
            occurrence.origem(),
            occurrence.dispositivo(),
            occurrence.clientTimestamp(),
            occurrence.serverTimestamp(),
            occurrence.referenciaId(),
            projection.totalQuantity(),
            projection.categoryQuantities(),
            isBlind ? null : item.saldoSnapshot(),
            progress
        );
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível registrar a ocorrência de contagem.", error);
    }
  }

  public static ItemProjection calculateItemProjection(List<OccurrenceRecord> occurrences) {
    if (occurrences == null || occurrences.isEmpty()) {
      return new ItemProjection(0, Map.of(), null, null);
    }

    Map<String, Integer> categoryTotals = new LinkedHashMap<>();
    categoryTotals.put("BOA", 0);
    categoryTotals.put("AVARIA", 0);
    categoryTotals.put("ASSISTENCIA", 0);
    categoryTotals.put("OUTROS", 0);

    Long lastId = null;
    Instant lastTs = null;

    for (OccurrenceRecord occ : occurrences) {
      String cat = occ.categoria();
      int currentVal = categoryTotals.getOrDefault(cat, 0);

      switch (occ.tipoAcao()) {
        case "DEFINIR", "CORRECAO" -> categoryTotals.put(cat, occ.quantidade());
        case "SOMAR" -> categoryTotals.put(cat, currentVal + occ.quantidade());
        default -> categoryTotals.put(cat, occ.quantidade());
      }
      lastId = occ.id();
      lastTs = occ.serverTimestamp();
    }

    int total = categoryTotals.values().stream().mapToInt(Integer::intValue).sum();
    return new ItemProjection(total, Map.copyOf(categoryTotals), lastId, lastTs);
  }

  private OccurrenceRecord insertOccurrenceIdempotent(
      Connection connection,
      long roundId,
      long itemId,
      String sku,
      int quantidade,
      String categoria,
      String tipoAcao,
      String operador,
      UUID clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Long referenciaId
  ) throws SQLException {
    String insertSql = """
        INSERT INTO ocorrencias_contagem
          (rodada_id, inventario_item_id, sku, quantidade, categoria, tipo_acao, operador,
           client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
        ON CONFLICT (client_event_id) DO NOTHING
        RETURNING id, rodada_id, inventario_item_id, sku, quantidade, categoria, tipo_acao,
                  operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id
        """;

    try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
      statement.setLong(1, roundId);
      statement.setLong(2, itemId);
      statement.setString(3, sku);
      statement.setInt(4, quantidade);
      statement.setString(5, categoria);
      statement.setString(6, tipoAcao);
      statement.setString(7, operador);
      statement.setObject(8, clientEventId);
      statement.setString(9, origem);
      statement.setString(10, dispositivo);
      statement.setTimestamp(11, clientTimestamp == null ? null : Timestamp.from(clientTimestamp));
      if (referenciaId == null) {
        statement.setNull(12, java.sql.Types.BIGINT);
      } else {
        statement.setLong(12, referenciaId);
      }

      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          return mapOccurrence(result);
        }
      }
    }

    String findSql = """
        SELECT id, rodada_id, inventario_item_id, sku, quantidade, categoria, tipo_acao,
               operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id
        FROM ocorrencias_contagem
        WHERE client_event_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(findSql)) {
      statement.setObject(1, clientEventId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          return mapOccurrence(result);
        }
        throw new DatabaseException("Falha de consistência ao salvar e buscar ocorrência por client_event_id.", null);
      }
    }
  }

  private void validateReferenceOccurrence(Connection connection, long roundId, long itemId, long refId)
      throws SQLException {
    String sql = "SELECT id, rodada_id, inventario_item_id FROM ocorrencias_contagem WHERE id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, refId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new ValidationException("Ocorrência de referência " + refId + " não encontrada.");
        }
        if (result.getLong("rodada_id") != roundId || result.getLong("inventario_item_id") != itemId) {
          throw new ValidationException("Ocorrência de referência não pertence a este item e rodada.");
        }
      }
    }
  }

  private CountingProgress calculateProgress(Connection connection, long inventoryId, long roundId)
      throws SQLException {
    String totalSql = "SELECT COUNT(*) FROM inventario_itens WHERE inventario_id = ?";
    int totalItems = 0;
    try (PreparedStatement statement = connection.prepareStatement(totalSql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) totalItems = result.getInt(1);
      }
    }

    String countedSql = """
        SELECT COUNT(DISTINCT inventario_item_id)
        FROM ocorrencias_contagem
        WHERE rodada_id = ?
        """;
    int countedItems = 0;
    try (PreparedStatement statement = connection.prepareStatement(countedSql)) {
      statement.setLong(1, roundId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) countedItems = result.getInt(1);
      }
    }

    int pendingItems = totalItems - countedItems;
    int percent = totalItems == 0 ? 0 : Math.round((countedItems * 100f) / totalItems);
    return new CountingProgress(totalItems, countedItems, pendingItems, percent);
  }

  private RoundRecord findActiveRound(Connection connection, long inventoryId) throws SQLException {
    String sql = """
        SELECT id, inventario_id, numero, tipo, status, iniciada_por, iniciada_em
        FROM rodadas_contagem
        WHERE inventario_id = ? AND status = 'EM_ANDAMENTO'
        ORDER BY numero DESC
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        return new RoundRecord(
            result.getLong("id"),
            result.getLong("inventario_id"),
            result.getInt("numero"),
            result.getString("tipo"),
            result.getString("status"),
            result.getString("iniciada_por"),
            result.getTimestamp("iniciada_em").toInstant()
        );
      }
    }
  }

  private RoundRecord requireRound(Connection connection, long roundId, long inventoryId) throws SQLException {
    String sql = """
        SELECT id, inventario_id, numero, tipo, status, iniciada_por, iniciada_em
        FROM rodadas_contagem
        WHERE id = ? AND inventario_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      statement.setLong(2, inventoryId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFoundException("Rodada de contagem não encontrada neste inventário.");
        return new RoundRecord(
            result.getLong("id"),
            result.getLong("inventario_id"),
            result.getInt("numero"),
            result.getString("tipo"),
            result.getString("status"),
            result.getString("iniciada_por"),
            result.getTimestamp("iniciada_em").toInstant()
        );
      }
    }
  }

  private InventoryHeader requireInventory(Connection connection, long inventoryId, String branchCode)
      throws SQLException {
    String sql = """
        SELECT i.id, i.nome, i.modo, i.status, f.codigo AS filial_codigo
        FROM inventarios i
        JOIN filiais f ON f.id = i.filial_id
        WHERE i.id = ? AND f.codigo = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setString(2, branchCode);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFoundException("Inventário não encontrado para esta filial.");
        return new InventoryHeader(
            result.getLong("id"),
            result.getString("nome"),
            result.getString("modo"),
            result.getString("status"),
            result.getString("filial_codigo")
        );
      }
    }
  }

  private void requireBranch(Connection connection, String branchCode) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT ativa FROM filiais WHERE codigo = ?")) {
      statement.setString(1, branchCode);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFoundException("Filial " + branchCode + " não encontrada.");
        if (!result.getBoolean("ativa")) throw new ValidationException("A filial " + branchCode + " está inativa.");
      }
    }
  }

  private List<ItemRecord> loadInventoryItems(Connection connection, long inventoryId, String search)
      throws SQLException {
    String filter = "";
    if (search != null && !search.isBlank()) {
      filter = " AND (LOWER(sku) LIKE ? OR LOWER(descricao_snapshot) LIKE ?)";
    }
    String sql = "SELECT id, sku, descricao_snapshot, saldo_snapshot FROM inventario_itens WHERE inventario_id = ?"
        + filter + " ORDER BY sku ASC";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      if (!filter.isEmpty()) {
        String query = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        statement.setString(2, query);
        statement.setString(3, query);
      }
      try (ResultSet result = statement.executeQuery()) {
        List<ItemRecord> list = new ArrayList<>();
        while (result.next()) {
          list.add(new ItemRecord(
              result.getLong("id"),
              result.getString("sku"),
              result.getString("descricao_snapshot"),
              result.getInt("saldo_snapshot")
          ));
        }
        return list;
      }
    }
  }

  private ItemRecord findItemBySku(Connection connection, long inventoryId, String sku) throws SQLException {
    String sql = "SELECT id, sku, descricao_snapshot, saldo_snapshot FROM inventario_itens WHERE inventario_id = ? AND sku = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setString(2, sku.trim());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        return new ItemRecord(
            result.getLong("id"),
            result.getString("sku"),
            result.getString("descricao_snapshot"),
            result.getInt("saldo_snapshot")
        );
      }
    }
  }

  private Map<Long, List<OccurrenceRecord>> loadRoundOccurrences(Connection connection, long roundId)
      throws SQLException {
    String sql = """
        SELECT id, rodada_id, inventario_item_id, sku, quantidade, categoria, tipo_acao,
               operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id
        FROM ocorrencias_contagem
        WHERE rodada_id = ?
        ORDER BY server_timestamp ASC, id ASC
        """;
    Map<Long, List<OccurrenceRecord>> map = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          OccurrenceRecord rec = mapOccurrence(result);
          map.computeIfAbsent(rec.inventarioItemId(), k -> new ArrayList<>()).add(rec);
        }
      }
    }
    return map;
  }

  private List<OccurrenceRecord> loadItemOccurrences(Connection connection, long roundId, long itemId)
      throws SQLException {
    String sql = """
        SELECT id, rodada_id, inventario_item_id, sku, quantidade, categoria, tipo_acao,
               operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id
        FROM ocorrencias_contagem
        WHERE rodada_id = ? AND inventario_item_id = ?
        ORDER BY server_timestamp ASC, id ASC
        """;
    List<OccurrenceRecord> list = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      statement.setLong(2, itemId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          list.add(mapOccurrence(result));
        }
      }
    }
    return list;
  }

  private OccurrenceRecord mapOccurrence(ResultSet result) throws SQLException {
    Timestamp clientTs = result.getTimestamp("client_timestamp");
    Timestamp serverTs = result.getTimestamp("server_timestamp");
    long refId = result.getLong("referencia_id");
    Long nullableRefId = result.wasNull() ? null : refId;

    return new OccurrenceRecord(
        result.getLong("id"),
        result.getLong("rodada_id"),
        result.getLong("inventario_item_id"),
        result.getString("sku"),
        result.getInt("quantidade"),
        result.getString("categoria"),
        result.getString("tipo_acao"),
        result.getString("operador"),
        (UUID) result.getObject("client_event_id"),
        result.getString("origem"),
        result.getString("dispositivo"),
        clientTs == null ? null : clientTs.toInstant(),
        serverTs == null ? Instant.now() : serverTs.toInstant(),
        nullableRefId
    );
  }

  private Connection connect() throws SQLException {
    return database.username().isBlank()
        ? DriverManager.getConnection(database.url())
        : DriverManager.getConnection(database.url(), database.username(), database.password());
  }

  private static String normalizeBranchCode(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.matches("\\d{1,20}")) throw new ValidationException("Código de filial inválido.");
    return normalized;
  }

  private static String required(String value, String message) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()) throw new ValidationException(message);
    return normalized;
  }

  private static String enumValue(String value, Set<String> allowed, String message) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) throw new ValidationException(message);
    return normalized;
  }

  // Records and DTOs
  public record RecordOccurrenceCommand(
      String sku,
      int quantidade,
      String categoria,
      String tipoAcao,
      Object clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Long referenciaId
  ) {}

  public record CountingProgress(int totalSkus, int contados, int pendentes, int percentual) {}

  public record RoundDetail(
      long id,
      long inventarioId,
      int numero,
      String tipo,
      String status,
      String iniciadaPor,
      Instant iniciadaEm,
      String inventarioNome,
      String inventarioModo,
      String inventarioStatus,
      CountingProgress progresso
  ) {}

  public record CountingItem(
      long id,
      String sku,
      String descricao,
      Integer saldoSnapshot,
      int quantidadeContada,
      Map<String, Integer> categorias,
      String estado,
      Long ultimaOcorrenciaId,
      Instant ultimaLeituraEm
  ) {}

  public record ItemListResult(
      long inventarioId,
      String inventarioNome,
      String inventarioModo,
      String inventarioStatus,
      long rodadaAtivaId,
      CountingProgress progresso,
      List<CountingItem> itens
  ) {}

  public record OccurrenceResult(
      long id,
      long rodadaId,
      long inventarioItemId,
      String sku,
      int quantidade,
      String categoria,
      String tipoAcao,
      String operador,
      UUID clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Instant serverTimestamp,
      Long referenciaId,
      int quantidadeItemProjetada,
      Map<String, Integer> categoriasItem,
      Integer saldoSnapshot,
      CountingProgress progresso
  ) {}

  public record ItemProjection(
      int totalQuantity,
      Map<String, Integer> categoryQuantities,
      Long lastOccurrenceId,
      Instant lastOccurrenceTimestamp
  ) {}

  private record RoundRecord(
      long id,
      long inventarioId,
      int numero,
      String tipo,
      String status,
      String iniciadaPor,
      Instant iniciadaEm
  ) {}

  private record InventoryHeader(long id, String nome, String modo, String status, String filialCodigo) {}
  private record ItemRecord(long id, String sku, String descricao, int saldoSnapshot) {}

  public record OccurrenceRecord(
      long id,
      long rodadaId,
      long inventarioItemId,
      String sku,
      int quantidade,
      String categoria,
      String tipoAcao,
      String operador,
      UUID clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Instant serverTimestamp,
      Long referenciaId
  ) {}

  // Exceptions
  public static class CountingException extends RuntimeException {
    private final int status;
    CountingException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }
  public static final class ValidationException extends CountingException {
    ValidationException(String message) { super(400, message); }
  }
  public static final class NotFoundException extends CountingException {
    NotFoundException(String message) { super(404, message); }
  }
  public static final class ConflictException extends CountingException {
    ConflictException(String message) { super(409, message); }
  }
  public static final class DatabaseException extends CountingException {
    DatabaseException(String message, Throwable cause) { super(503, message); initCause(cause); }
  }
}
