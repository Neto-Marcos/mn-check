package br.com.mncheck;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class InventorySessionService {
  private static final Set<String> TYPES = Set.of("GERAL", "PARCIAL", "AUDITORIA", "CICLICA");
  private static final Set<String> MODES = Set.of("NORMAL", "CEGO");
  private static final Map<String, Set<String>> TRANSITIONS = Map.of(
      "RASCUNHO", Set.of("ABERTO", "CANCELADO"),
      "ABERTO", Set.of("EM_CONTAGEM", "CANCELADO")
  );

  private final DatabaseUrlParser.JdbcConfig database;

  public InventorySessionService() {
    this(System.getenv("DATABASE_URL"));
  }

  InventorySessionService(String databaseUrl) {
    this.database = DatabaseUrlParser.parse(databaseUrl);
  }

  public InventoryDetail create(CreateCommand command, String actor) {
    String branchCode = normalizeBranchCode(command.branchCode());
    String name = required(command.nome(), "Informe o nome do inventário.");
    String type = enumValue(command.tipo(), TYPES, "Tipo de inventário inválido.");
    String mode = enumValue(command.modo(), MODES, "Modo de inventário inválido.");
    List<String> requestedSkus = normalizeSkus(command.skus());
    if ("PARCIAL".equals(type) && requestedSkus.isEmpty()) {
      throw new ValidationException("Inventário parcial exige pelo menos um SKU.");
    }
    if (!"PARCIAL".equals(type) && !requestedSkus.isEmpty()) {
      throw new ValidationException("A seleção de SKUs é permitida somente no inventário PARCIAL.");
    }

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        Branch branch = requireBranch(connection, branchCode);
        requireImport(connection, command.importacaoSaldoId(), branch.id());
        long inventoryId = insertInventory(connection, branch.id(), command.importacaoSaldoId(),
            name, type, mode, safeActor(actor));
        int copied = copySnapshot(connection, inventoryId, branch.id(), command.importacaoSaldoId(),
            "PARCIAL".equals(type) ? requestedSkus : null);
        if (copied == 0) {
          throw new ValidationException("A importação selecionada não possui itens para o inventário.");
        }
        if ("PARCIAL".equals(type) && copied != requestedSkus.size()) {
          throw new ValidationException("Um ou mais SKUs selecionados não pertencem à importação informada.");
        }
        connection.commit();
        return loadDetail(inventoryId, branchCode);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw databaseError("Não foi possível criar a sessão de inventário.", error);
    }
  }

  public List<InventorySummary> list(String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String sql = summarySelect() + """
        WHERE f.codigo = ?
        GROUP BY i.id, f.codigo
        ORDER BY i.criado_em DESC, i.id DESC
        """;
    try (Connection connection = connect()) {
      requireBranch(connection, normalizedBranch);
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizedBranch);
        try (ResultSet result = statement.executeQuery()) {
          List<InventorySummary> inventories = new ArrayList<>();
          while (result.next()) inventories.add(summary(result));
          return inventories;
        }
      }
    } catch (SQLException error) {
      throw databaseError("Não foi possível listar os inventários.", error);
    }
  }

  public InventoryDetail loadDetail(long inventoryId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String sql = summarySelect() + """
        WHERE i.id = ? AND f.codigo = ?
        GROUP BY i.id, f.codigo
        """;
    try (Connection connection = connect()) {
      requireBranch(connection, normalizedBranch);
      InventorySummary inventory;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, inventoryId);
        statement.setString(2, normalizedBranch);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) throw new NotFoundException("Inventário não encontrado nesta filial.");
          inventory = summary(result);
        }
      }
      boolean isBlind = "CEGO".equalsIgnoreCase(inventory.modo());
      return new InventoryDetail(inventory, loadItems(connection, inventoryId, isBlind));
    } catch (SQLException error) {
      throw databaseError("Não foi possível carregar o inventário.", error);
    }
  }

  public InventoryDetail open(long inventoryId, String branchCode, long expectedVersion, String actor) {
    return transition(inventoryId, branchCode, expectedVersion, "ABERTO", actor);
  }

  public InventoryDetail start(long inventoryId, String branchCode, long expectedVersion, String actor) {
    return transition(inventoryId, branchCode, expectedVersion, "EM_CONTAGEM", actor);
  }

  public InventoryDetail cancel(long inventoryId, String branchCode, long expectedVersion, String actor) {
    return transition(inventoryId, branchCode, expectedVersion, "CANCELADO", actor);
  }

  private InventoryDetail transition(long inventoryId, String branchCode, long expectedVersion,
      String targetStatus, String actor) {
    if (expectedVersion < 0) throw new ValidationException("Versão esperada inválida.");
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        Branch branch = requireBranch(connection, normalizedBranch);
        SessionState current = lockState(connection, inventoryId, branch.id());
        if (current.version() != expectedVersion) {
          throw new VersionConflictException(expectedVersion, current.version());
        }
        if (!TRANSITIONS.getOrDefault(current.status(), Set.of()).contains(targetStatus)) {
          throw new InvalidTransitionException(current.status(), targetStatus);
        }
        updateState(connection, inventoryId, branch.id(), expectedVersion, targetStatus, safeActor(actor));
        if ("EM_CONTAGEM".equals(targetStatus)) {
          createInitialRound(connection, inventoryId, safeActor(actor));
        }
        connection.commit();
        return loadDetail(inventoryId, normalizedBranch);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw databaseError("Não foi possível alterar o estado do inventário.", error);
    }
  }

  private void updateState(Connection connection, long inventoryId, long branchId, long expectedVersion,
      String targetStatus, String actor) throws SQLException {
    String lifecycle = switch (targetStatus) {
      case "ABERTO" -> ", aberto_por = ?, aberto_em = now()";
      case "CANCELADO" -> ", cancelado_por = ?, cancelado_em = now()";
      default -> "";
    };
    String sql = "UPDATE inventarios SET status = ?, version = version + 1" + lifecycle
        + " WHERE id = ? AND filial_id = ? AND version = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      statement.setString(index++, targetStatus);
      if (!lifecycle.isEmpty()) statement.setString(index++, actor);
      statement.setLong(index++, inventoryId);
      statement.setLong(index++, branchId);
      statement.setLong(index, expectedVersion);
      if (statement.executeUpdate() != 1) {
        throw new VersionConflictException(expectedVersion, -1);
      }
    }
  }

  private SessionState lockState(Connection connection, long inventoryId, long branchId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT status, version FROM inventarios WHERE id = ? AND filial_id = ? FOR UPDATE")) {
      statement.setLong(1, inventoryId);
      statement.setLong(2, branchId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFoundException("Inventário não encontrado nesta filial.");
        return new SessionState(result.getString("status"), result.getLong("version"));
      }
    }
  }

  private long insertInventory(Connection connection, long branchId, long importId, String name,
      String type, String mode, String actor) throws SQLException {
    String sql = """
        INSERT INTO inventarios
          (filial_id, importacao_saldo_id, nome, tipo, modo, status, version, criado_por, criado_em)
        VALUES (?, ?, ?, ?, ?, 'RASCUNHO', 0, ?, now())
        RETURNING id
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, branchId);
      statement.setLong(2, importId);
      statement.setString(3, name);
      statement.setString(4, type);
      statement.setString(5, mode);
      statement.setString(6, actor);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("A sessão não retornou identificador.");
        return result.getLong(1);
      }
    }
  }

  private int copySnapshot(Connection connection, long inventoryId, long branchId, long importId,
      List<String> selectedSkus) throws SQLException {
    String filter = selectedSkus == null ? "" : " AND s.sku = ANY (?)";
    String sql = """
        INSERT INTO inventario_itens
          (inventario_id, sku, descricao_snapshot, saldo_snapshot, estado, criado_em)
        SELECT ?, s.sku, COALESCE(NULLIF(e.descricao, ''), 'Produto ' || s.sku),
               s.saldo, 'PENDENTE', now()
        FROM saldos s
        JOIN importacoes_saldo imp ON imp.id = s.importacao_id
        LEFT JOIN estoque_produtos e ON e.filial_id = imp.filial_id AND e.sku = s.sku
        WHERE s.importacao_id = ? AND imp.filial_id = ?
        """ + filter;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setLong(2, importId);
      statement.setLong(3, branchId);
      if (selectedSkus != null) {
        Array skus = connection.createArrayOf("varchar", selectedSkus.toArray(String[]::new));
        statement.setArray(4, skus);
      }
      return statement.executeUpdate();
    }
  }

  private void requireImport(Connection connection, long importId, long branchId) throws SQLException {
    if (importId <= 0) throw new ValidationException("Informe uma importação de saldo válida.");
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT filial_id FROM importacoes_saldo WHERE id = ?")) {
      statement.setLong(1, importId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFoundException("Importação de saldo não encontrada.");
        if (result.getLong(1) != branchId) {
          throw new BranchMismatchException("A importação de saldo pertence a outra filial.");
        }
      }
    }
  }

  private Branch requireBranch(Connection connection, String branchCode) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id, codigo, ativa FROM filiais WHERE codigo = ?")) {
      statement.setString(1, branchCode);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFoundException("Filial " + branchCode + " não encontrada.");
        if (!result.getBoolean("ativa")) throw new InactiveBranchException(branchCode);
        return new Branch(result.getLong("id"), result.getString("codigo"));
      }
    }
  }

  private void createInitialRound(Connection connection, long inventoryId, String actor) throws SQLException {
    String sql = """
        INSERT INTO rodadas_contagem (inventario_id, numero, tipo, status, iniciada_por, iniciada_em, created_at)
        VALUES (?, 1, 'CONTAGEM', 'EM_ANDAMENTO', ?, now(), now())
        ON CONFLICT (inventario_id, numero) DO NOTHING
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setString(2, actor);
      statement.executeUpdate();
    }
  }

  private List<InventoryItem> loadItems(Connection connection, long inventoryId, boolean isBlind) throws SQLException {
    String sql = """
        SELECT id, sku, descricao_snapshot, saldo_snapshot, estado, criado_em
        FROM inventario_itens WHERE inventario_id = ? ORDER BY sku
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      try (ResultSet result = statement.executeQuery()) {
        List<InventoryItem> items = new ArrayList<>();
        while (result.next()) {
          Integer saldoSnapshot = isBlind ? null : result.getInt("saldo_snapshot");
          items.add(new InventoryItem(result.getLong("id"), result.getString("sku"),
              result.getString("descricao_snapshot"), saldoSnapshot,
              result.getString("estado"), instant(result, "criado_em")));
        }
        return items;
      }
    }
  }

  private static String summarySelect() {
    return """
        SELECT i.id, f.codigo AS filial_codigo, i.importacao_saldo_id, i.nome, i.tipo, i.modo,
               i.status, i.version, i.criado_por, i.criado_em, i.aberto_por, i.aberto_em,
               i.encerrado_por, i.encerrado_em, i.cancelado_por, i.cancelado_em,
               COUNT(ii.id) AS total_skus, COALESCE(SUM(ii.saldo_snapshot), 0) AS total_unidades
        FROM inventarios i
        JOIN filiais f ON f.id = i.filial_id
        LEFT JOIN inventario_itens ii ON ii.inventario_id = i.id
        """;
  }

  private InventorySummary summary(ResultSet result) throws SQLException {
    return new InventorySummary(result.getLong("id"), result.getString("filial_codigo"),
        result.getLong("importacao_saldo_id"), result.getString("nome"), result.getString("tipo"),
        result.getString("modo"), result.getString("status"), result.getLong("version"),
        result.getString("criado_por"), instant(result, "criado_em"), result.getString("aberto_por"),
        nullableInstant(result, "aberto_em"), result.getString("encerrado_por"),
        nullableInstant(result, "encerrado_em"), result.getString("cancelado_por"),
        nullableInstant(result, "cancelado_em"), result.getInt("total_skus"),
        result.getLong("total_unidades"));
  }

  private Connection connect() throws SQLException {
    return database.username().isBlank()
        ? DriverManager.getConnection(database.url())
        : DriverManager.getConnection(database.url(), database.username(), database.password());
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return result.getTimestamp(column).toInstant();
  }

  private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String normalizeBranchCode(String value) {
    String normalized = value == null ? "" : value.trim();
    if (!normalized.matches("\\d{1,20}")) throw new ValidationException("Código de filial inválido.");
    return normalized;
  }

  private static List<String> normalizeSkus(List<String> values) {
    if (values == null) return List.of();
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      String sku = value == null ? "" : value.trim();
      if (sku.isBlank()) throw new ValidationException("SKU parcial inválido.");
      normalized.add(sku);
    }
    return List.copyOf(normalized);
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

  private static String safeActor(String actor) {
    return required(actor, "Usuário responsável não informado.");
  }

  private static DatabaseException databaseError(String message, SQLException error) {
    return new DatabaseException(message, error);
  }

  public record CreateCommand(long importacaoSaldoId, String branchCode, String nome, String tipo,
                              String modo, List<String> skus) {}
  public record InventoryItem(long id, String sku, String descricaoSnapshot, Integer saldoSnapshot,
                              String estado, Instant criadoEm) {}
  public record InventorySummary(long id, String branchCode, long importacaoSaldoId, String nome,
                                 String tipo, String modo, String status, long version,
                                 String criadoPor, Instant criadoEm, String abertoPor, Instant abertoEm,
                                 String encerradoPor, Instant encerradoEm, String canceladoPor,
                                 Instant canceladoEm, int totalSkus, long totalUnidades) {}
  public record InventoryDetail(InventorySummary inventory, List<InventoryItem> items) {}
  private record Branch(long id, String code) {}
  private record SessionState(String status, long version) {}

  public static class InventoryException extends RuntimeException {
    private final int status;
    InventoryException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }
  public static final class ValidationException extends InventoryException {
    ValidationException(String message) { super(400, message); }
  }
  public static final class NotFoundException extends InventoryException {
    NotFoundException(String message) { super(404, message); }
  }
  public static final class BranchMismatchException extends InventoryException {
    BranchMismatchException(String message) { super(422, message); }
  }
  public static final class InactiveBranchException extends InventoryException {
    InactiveBranchException(String code) { super(422, "A filial " + code + " está inativa."); }
  }
  public static final class InvalidTransitionException extends InventoryException {
    InvalidTransitionException(String current, String target) {
      super(409, "Transição de " + current + " para " + target + " não permitida.");
    }
  }
  public static final class VersionConflictException extends InventoryException {
    VersionConflictException(long expected, long current) {
      super(409, current < 0 ? "A sessão foi alterada por outro usuário."
          : "Versão divergente: esperada " + expected + ", atual " + current + ".");
    }
  }
  public static final class DatabaseException extends InventoryException {
    DatabaseException(String message, Throwable cause) { super(503, message); initCause(cause); }
  }
}
