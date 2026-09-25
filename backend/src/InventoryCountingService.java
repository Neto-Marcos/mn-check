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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

@Service
public class InventoryCountingService {
  public static final Set<String> CATEGORIES = Set.of("BOA", "AVARIA", "ASSISTENCIA", "OUTROS");
  public static final Set<String> LOCATIONS = Set.of("GERAL", "VENDAS", "DEPOSITO", "TROCAS", "OUTRO");
  private static final Set<String> ACTION_TYPES = Set.of("DEFINIR", "SOMAR", "CORRECAO");
  private static final Set<String> ORIGINS = Set.of("SCANNER", "MANUAL", "OFFLINE");

  private final DatabaseUrlParser.JdbcConfig database;
  private final ObjectMapper objectMapper;

  public InventoryCountingService() {
    this(System.getenv("DATABASE_URL"), new ObjectMapper());
  }

  public InventoryCountingService(ObjectMapper objectMapper) {
    this(System.getenv("DATABASE_URL"), objectMapper != null ? objectMapper : new ObjectMapper());
  }

  public InventoryCountingService(String databaseUrl) {
    this(databaseUrl, new ObjectMapper());
  }

  public InventoryCountingService(String databaseUrl, ObjectMapper objectMapper) {
    this.database = DatabaseUrlParser.parse(databaseUrl);
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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

      boolean isBlind = "CEGO".equalsIgnoreCase(inventory.modo()) || (round != null && round.numero() >= 2);
      boolean hasScope = round != null && hasRoundItemScope(connection, round.id());

      List<ItemRecord> rawItems = hasScope
          ? loadRoundScopeItems(connection, round.id(), search)
          : loadInventoryItems(connection, inventoryId, search);

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
            projection.locationDetails(),
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

    String rawLocation = command.localizacao() == null || command.localizacao().isBlank()
        ? "GERAL" : command.localizacao();
    String localizacao = enumValue(rawLocation, LOCATIONS, "Localização inválida.");

    String rawCategory = command.categoria() == null || command.categoria().isBlank()
        ? "BOA" : command.categoria();
    String categoria = enumValue(rawCategory, CATEGORIES, "Categoria inválida.");

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
        if (!"EM_CONTAGEM".equalsIgnoreCase(inventory.status()) && !"EM_RECONTAGEM".equalsIgnoreCase(inventory.status())) {
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

        // Se a rodada tiver escopo restrito (ex: R2), valida se o item faz parte
        if (hasRoundItemScope(connection, round.id())) {
          if (!isItemInRoundScope(connection, round.id(), item.id())) {
            throw new ConflictException("SKU " + sku + " não pertence ao escopo desta rodada de recontagem.");
          }
        }

        // Validação da regra de GERAL: não permitir misturar GERAL com localizações detalhadas
        List<OccurrenceRecord> existingItemOccurrences = loadItemOccurrences(connection, round.id(), item.id());
        validateLocationMixing(existingItemOccurrences, localizacao);

        if (command.referenciaId() != null) {
          validateReferenceOccurrence(connection, round.id(), item.id(), command.referenciaId());
        }

        OccurrenceRecord occurrence = insertOccurrenceIdempotent(
            connection,
            round.id(),
            item.id(),
            sku,
            quantidade,
            localizacao,
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

        boolean isBlind = "CEGO".equalsIgnoreCase(inventory.modo()) || round.numero() >= 2;
        return new OccurrenceResult(
            occurrence.id(),
            occurrence.rodadaId(),
            occurrence.inventarioItemId(),
            occurrence.sku(),
            occurrence.quantidade(),
            occurrence.localizacao(),
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
            projection.locationDetails(),
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

  public RoundAuditResult closeRound(
      long inventoryId,
      long roundId,
      String branchCode,
      boolean force,
      String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranch(connection, normalizedBranch);
        InventoryHeader inventory = requireInventoryForUpdate(connection, inventoryId, normalizedBranch);
        if (!"EM_CONTAGEM".equalsIgnoreCase(inventory.status()) && !"EM_RECONTAGEM".equalsIgnoreCase(inventory.status())) {
          throw new ConflictException("Inventário não está em contagem (status atual: " + inventory.status() + ").");
        }

        RoundRecord round = requireRoundForUpdate(connection, roundId, inventoryId);
        if (!"EM_ANDAMENTO".equalsIgnoreCase(round.status())) {
          throw new ConflictException("Rodada " + round.numero() + " não está em andamento (status atual: " + round.status() + ").");
        }

        boolean hasScope = hasRoundItemScope(connection, round.id());
        List<ItemRecord> itemsInScope = hasScope
            ? loadRoundScopeItems(connection, round.id(), null)
            : loadInventoryItems(connection, inventoryId, null);

        Map<Long, List<OccurrenceRecord>> occurrencesByItem = loadRoundOccurrences(connection, round.id());

        int uncountedCount = 0;
        for (ItemRecord item : itemsInScope) {
          List<OccurrenceRecord> occs = occurrencesByItem.getOrDefault(item.id(), List.of());
          if (occs.isEmpty()) {
            uncountedCount++;
          }
        }

        if (uncountedCount > 0 && !force) {
          throw new UncountedItemsConflictException(
              uncountedCount,
              "Existem " + uncountedCount + " itens não contados. O encerramento normal foi bloqueado."
          );
        }

        // Finaliza a rodada
        String updateRoundSql = """
            UPDATE rodadas_contagem
            SET status = 'FINALIZADA',
                finalizada_por = ?,
                finalizada_em = now(),
                encerramento_forcado = ?,
                pendentes_no_fechamento = ?
            WHERE id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateRoundSql)) {
          statement.setString(1, safeActor);
          statement.setBoolean(2, uncountedCount > 0);
          statement.setInt(3, uncountedCount);
          statement.setLong(4, round.id());
          statement.executeUpdate();
        }

        // Persiste as apurações materializadas em apuracoes_rodada
        String insertAuditSql = """
            INSERT INTO apuracoes_rodada
              (rodada_id, inventario_item_id, sku, saldo_snapshot, contado,
               quantidade_fisica, diferenca, estado, detalhes_localizacao_condicao,
               apurado_em, apurado_por)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), ?)
            ON CONFLICT (rodada_id, inventario_item_id) DO UPDATE SET
              saldo_snapshot = EXCLUDED.saldo_snapshot,
              contado = EXCLUDED.contado,
              quantidade_fisica = EXCLUDED.quantidade_fisica,
              diferenca = EXCLUDED.diferenca,
              estado = EXCLUDED.estado,
              detalhes_localizacao_condicao = EXCLUDED.detalhes_localizacao_condicao,
              apurado_em = now(),
              apurado_por = EXCLUDED.apurado_por
            """;

        try (PreparedStatement statement = connection.prepareStatement(insertAuditSql)) {
          for (ItemRecord item : itemsInScope) {
            List<OccurrenceRecord> occs = occurrencesByItem.getOrDefault(item.id(), List.of());
            boolean contado = !occs.isEmpty();

            statement.setLong(1, round.id());
            statement.setLong(2, item.id());
            statement.setString(3, item.sku());
            statement.setInt(4, item.saldoSnapshot());
            statement.setBoolean(5, contado);

            if (contado) {
              ItemProjection proj = calculateItemProjection(occs);
              int qtdFisica = proj.totalQuantity();
              int diff = qtdFisica - item.saldoSnapshot();

              String estado;
              if (round.numero() == 1) {
                estado = (diff == 0) ? "CONFORME" : "DIVERGENTE";
              } else {
                estado = (diff == 0) ? "CONFORME_APOS_RECONTAGEM" : "DIVERGENCIA_CONFIRMADA";
              }

              statement.setInt(6, qtdFisica);
              statement.setInt(7, diff);
              statement.setString(8, estado);
              statement.setString(9, toJson(proj.locationDetails()));
            } else {
              statement.setNull(6, java.sql.Types.INTEGER);
              statement.setNull(7, java.sql.Types.INTEGER);
              statement.setString(8, "NAO_CONTADO");
              statement.setNull(9, java.sql.Types.VARCHAR);
            }

            statement.setString(10, safeActor);
            statement.addBatch();
          }
          statement.executeBatch();
        }

        connection.commit();
        return loadAuditInternal(connection, inventoryId, round.id(), normalizedBranch);
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível encerrar a rodada.", error);
    }
  }

  public RoundDetail createRecountRound(
      long inventoryId,
      String branchCode,
      List<Long> itemIds,
      String actor
  ) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    String safeActor = required(actor, "Usuário autenticado não informado.");

    if (itemIds == null || itemIds.isEmpty()) {
      throw new ValidationException("Selecione pelo menos um item para a recontagem.");
    }

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        requireBranch(connection, normalizedBranch);
        InventoryHeader inventory = requireInventoryForUpdate(connection, inventoryId, normalizedBranch);
        if (!"EM_CONTAGEM".equalsIgnoreCase(inventory.status())) {
          throw new ConflictException("Inventário não está em contagem para iniciar recontagem (status atual: " + inventory.status() + ").");
        }

        // Verifica se a Rodada 1 existe e está FINALIZADA
        RoundRecord r1 = findRoundByNumber(connection, inventoryId, 1);
        if (r1 == null || !"FINALIZADA".equalsIgnoreCase(r1.status())) {
          throw new ConflictException("A Rodada 1 deve estar finalizada antes de iniciar a recontagem.");
        }

        // Verifica se a Rodada 2 já existe
        RoundRecord r2Existing = findRoundByNumber(connection, inventoryId, 2);
        if (r2Existing != null) {
          throw new ConflictException("A Rodada 2 de recontagem já foi criada.");
        }

        // Carrega a apuração da Rodada 1 para validar itens
        Map<Long, AuditItemRecord> r1Audit = loadAuditRecordsForRound(connection, r1.id());

        // Valida que itens conformes não entram e que todos os itens pertencem à R1
        for (Long itemId : itemIds) {
          AuditItemRecord auditItem = r1Audit.get(itemId);
          if (auditItem == null) {
            throw new ValidationException("Item " + itemId + " não pertence à apuração da Rodada 1.");
          }
          if ("CONFORME".equalsIgnoreCase(auditItem.estado())) {
            throw new ValidationException("Itens com status CONFORME não podem ser incluídos na recontagem: SKU " + auditItem.sku());
          }
        }

        // Cria a Rodada 2
        String insertRoundSql = """
            INSERT INTO rodadas_contagem
              (inventario_id, numero, tipo, status, iniciada_por, iniciada_em, created_at)
            VALUES (?, 2, 'RECONTAGEM', 'EM_ANDAMENTO', ?, now(), now())
            RETURNING id, inventario_id, numero, tipo, status, iniciada_por, iniciada_em
            """;
        long r2Id;
        try (PreparedStatement statement = connection.prepareStatement(insertRoundSql)) {
          statement.setLong(1, inventoryId);
          statement.setString(2, safeActor);
          try (ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) throw new SQLException("Falha ao gerar Rodada 2.");
            r2Id = rs.getLong("id");
          }
        }

        // Insere os itens elegíveis no escopo rodada_itens
        String insertScopeSql = """
            INSERT INTO rodada_itens
              (rodada_id, inventario_item_id, origem_motivo, created_at)
            VALUES (?, ?, ?, now())
            """;
        try (PreparedStatement statement = connection.prepareStatement(insertScopeSql)) {
          for (Long itemId : itemIds) {
            AuditItemRecord auditItem = r1Audit.get(itemId);
            String motivo = "DIVERGENTE".equalsIgnoreCase(auditItem.estado()) ? "DIVERGENCIA" : "NAO_CONTADO_R1";
            statement.setLong(1, r2Id);
            statement.setLong(2, itemId);
            statement.setString(3, motivo);
            statement.addBatch();
          }
          statement.executeBatch();
        }

        // Atualiza o inventário para EM_RECONTAGEM
        String updateInvSql = "UPDATE inventarios SET status = 'EM_RECONTAGEM', version = version + 1 WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(updateInvSql)) {
          statement.setLong(1, inventoryId);
          statement.executeUpdate();
        }

        connection.commit();

        CountingProgress progress = calculateProgress(connection, inventoryId, r2Id);
        return new RoundDetail(
            r2Id,
            inventoryId,
            2,
            "RECONTAGEM",
            "EM_ANDAMENTO",
            safeActor,
            Instant.now(),
            inventory.nome(),
            inventory.modo(),
            "EM_RECONTAGEM",
            progress
        );
      } catch (RuntimeException | SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível criar a Rodada 2 de recontagem.", error);
    }
  }

  public RoundAuditResult getRoundAudit(long inventoryId, long roundId, String branchCode) {
    String normalizedBranch = normalizeBranchCode(branchCode);
    try (Connection connection = connect()) {
      requireBranch(connection, normalizedBranch);
      return loadAuditInternal(connection, inventoryId, roundId, normalizedBranch);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar a apuração da rodada.", error);
    }
  }

  private RoundAuditResult loadAuditInternal(Connection connection, long inventoryId, long roundId, String branchCode)
      throws SQLException {
    InventoryHeader inventory = requireInventory(connection, inventoryId, branchCode);
    RoundRecord round = requireRound(connection, roundId, inventoryId);

    String roundAuditMetaSql = """
        SELECT finalizada_em, finalizada_por, encerramento_forcado, pendentes_no_fechamento
        FROM rodadas_contagem WHERE id = ?
        """;
    Instant finalizadaEm = null;
    String finalizadaPor = "";
    boolean encerramentoForcado = false;
    int pendentesNoFechamento = 0;

    try (PreparedStatement statement = connection.prepareStatement(roundAuditMetaSql)) {
      statement.setLong(1, roundId);
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) {
          Timestamp ts = rs.getTimestamp("finalizada_em");
          finalizadaEm = ts == null ? null : ts.toInstant();
          finalizadaPor = rs.getString("finalizada_por");
          encerramentoForcado = rs.getBoolean("encerramento_forcado");
          pendentesNoFechamento = rs.getInt("pendentes_no_fechamento");
        }
      }
    }

    String itemsAuditSql = """
        SELECT ar.id AS apuracao_id, ar.inventario_item_id, ar.sku, ii.descricao_snapshot, ar.saldo_snapshot,
               ar.contado, ar.quantidade_fisica, ar.diferenca, ar.estado,
               ar.detalhes_localizacao_condicao
        FROM apuracoes_rodada ar
        JOIN inventario_itens ii ON ii.id = ar.inventario_item_id
        WHERE ar.rodada_id = ?
        ORDER BY ar.sku ASC
        """;

    List<AuditItem> items = new ArrayList<>();
    int conformes = 0;
    int divergentes = 0;
    int naoContados = 0;
    int conformesAposRecontagem = 0;
    int divergenciasConfirmadas = 0;
    int totalFalta = 0;
    int totalSobra = 0;

    try (PreparedStatement statement = connection.prepareStatement(itemsAuditSql)) {
      statement.setLong(1, roundId);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          long apuracaoId = rs.getLong("apuracao_id");
          long itemId = rs.getLong("inventario_item_id");
          String sku = rs.getString("sku");
          String descricao = rs.getString("descricao_snapshot");
          int saldo = rs.getInt("saldo_snapshot");
          boolean contado = rs.getBoolean("contado");

          int qFisicaRaw = rs.getInt("quantidade_fisica");
          Integer quantidadeFisica = rs.wasNull() ? null : qFisicaRaw;

          int diffRaw = rs.getInt("diferenca");
          Integer diferenca = rs.wasNull() ? null : diffRaw;

          String estado = rs.getString("estado");
          String jsonDetails = rs.getString("detalhes_localizacao_condicao");
          Map<String, Object> detalhes = Map.of();
          if (jsonDetails != null && !jsonDetails.isBlank()) {
            try {
              detalhes = objectMapper.readValue(jsonDetails, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
          }

          switch (estado) {
            case "CONFORME" -> conformes++;
            case "DIVERGENTE" -> divergentes++;
            case "NAO_CONTADO" -> naoContados++;
            case "CONFORME_APOS_RECONTAGEM" -> conformesAposRecontagem++;
            case "DIVERGENCIA_CONFIRMADA" -> divergenciasConfirmadas++;
          }

          if (diferenca != null) {
            if (diferenca < 0) totalFalta += Math.abs(diferenca);
            else if (diferenca > 0) totalSobra += diferenca;
          }

          items.add(new AuditItem(
              apuracaoId, itemId, sku, descricao, saldo, contado, quantidadeFisica, diferenca, estado, detalhes
          ));
        }
      }
    }

    AuditSummary resumo = new AuditSummary(
        items.size(), conformes, divergentes, naoContados,
        conformesAposRecontagem, divergenciasConfirmadas, totalFalta, totalSobra
    );

    return new RoundAuditResult(
        inventory.id(),
        inventory.nome(),
        inventory.modo(),
        inventory.status(),
        round.id(),
        round.numero(),
        round.tipo(),
        round.status(),
        finalizadaEm,
        finalizadaPor,
        encerramentoForcado,
        pendentesNoFechamento,
        resumo,
        items
    );
  }

  public static void validateLocationMixing(List<OccurrenceRecord> existingOccurrences, String newLocation) {
    if (existingOccurrences == null || existingOccurrences.isEmpty()) {
      return;
    }
    boolean isNewGeral = "GERAL".equalsIgnoreCase(newLocation);
    for (OccurrenceRecord occ : existingOccurrences) {
      String occLoc = occ.localizacao() == null || occ.localizacao().isBlank() ? "GERAL" : occ.localizacao();
      boolean isOccGeral = "GERAL".equalsIgnoreCase(occLoc);
      if (isOccGeral && !isNewGeral) {
        throw new ConflictException("Item já possui contagem em GERAL. Não é permitido misturar com localizações detalhadas.");
      }
      if (!isOccGeral && isNewGeral) {
        throw new ConflictException("Item já possui contagem em localização detalhada. Não é permitido registrar em GERAL.");
      }
    }
  }

  public static ItemProjection calculateItemProjection(List<OccurrenceRecord> occurrences) {
    if (occurrences == null || occurrences.isEmpty()) {
      Map<String, Integer> emptyCat = new LinkedHashMap<>();
      for (String c : CATEGORIES) emptyCat.put(c, 0);
      return new ItemProjection(0, Map.copyOf(emptyCat), Map.of(), null, null);
    }

    Map<String, Map<String, Integer>> locationMap = new LinkedHashMap<>();
    Long lastId = null;
    Instant lastTs = null;

    for (OccurrenceRecord occ : occurrences) {
      String loc = (occ.localizacao() == null || occ.localizacao().isBlank())
          ? "GERAL" : occ.localizacao().trim().toUpperCase(Locale.ROOT);
      String cat = (occ.categoria() == null || occ.categoria().isBlank())
          ? "BOA" : occ.categoria().trim().toUpperCase(Locale.ROOT);

      Map<String, Integer> locBuckets = locationMap.computeIfAbsent(loc, k -> {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String c : CATEGORIES) m.put(c, 0);
        return m;
      });

      int currentVal = locBuckets.getOrDefault(cat, 0);
      switch (occ.tipoAcao()) {
        case "DEFINIR", "CORRECAO" -> locBuckets.put(cat, occ.quantidade());
        case "SOMAR" -> locBuckets.put(cat, currentVal + occ.quantidade());
        default -> locBuckets.put(cat, occ.quantidade());
      }
      lastId = occ.id();
      lastTs = occ.serverTimestamp();
    }

    // Calcular totais planos por categoria (para manter compatibilidade regressiva de DTOs)
    Map<String, Integer> categoryTotals = new LinkedHashMap<>();
    for (String c : CATEGORIES) categoryTotals.put(c, 0);
    for (Map<String, Integer> buckets : locationMap.values()) {
      for (Map.Entry<String, Integer> entry : buckets.entrySet()) {
        categoryTotals.put(entry.getKey(), categoryTotals.getOrDefault(entry.getKey(), 0) + entry.getValue());
      }
    }

    int total = categoryTotals.values().stream().mapToInt(Integer::intValue).sum();
    return new ItemProjection(total, Map.copyOf(categoryTotals), Map.copyOf(locationMap), lastId, lastTs);
  }

  private String toJson(Object value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private OccurrenceRecord insertOccurrenceIdempotent(
      Connection connection,
      long roundId,
      long itemId,
      String sku,
      int quantidade,
      String localizacao,
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
          (rodada_id, inventario_item_id, sku, quantidade, localizacao, categoria, tipo_acao, operador,
           client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
        ON CONFLICT (client_event_id) DO NOTHING
        RETURNING id, rodada_id, inventario_item_id, sku, quantidade, localizacao, categoria, tipo_acao,
                  operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id
        """;

    try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
      statement.setLong(1, roundId);
      statement.setLong(2, itemId);
      statement.setString(3, sku);
      statement.setInt(4, quantidade);
      statement.setString(5, localizacao);
      statement.setString(6, categoria);
      statement.setString(7, tipoAcao);
      statement.setString(8, operador);
      statement.setObject(9, clientEventId);
      statement.setString(10, origem);
      statement.setString(11, dispositivo == null ? "" : dispositivo.trim());
      statement.setTimestamp(12, clientTimestamp == null ? null : Timestamp.from(clientTimestamp));
      statement.setObject(13, referenciaId);

      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          return mapOccurrence(result);
        }
      }
    }

    // Se já existia, recupera a ocorrência existente
    String selectSql = """
        SELECT id, rodada_id, inventario_item_id, sku, quantidade, localizacao, categoria, tipo_acao,
               operador, client_event_id, origem, dispositivo, client_timestamp, server_timestamp, referencia_id
        FROM ocorrencias_contagem
        WHERE client_event_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
      statement.setObject(1, clientEventId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          return mapOccurrence(result);
        }
      }
    }
    throw new SQLException("Não foi possível recuperar a ocorrência idempotente gravada.");
  }

  private void validateReferenceOccurrence(Connection connection, long roundId, long itemId, long refId)
      throws SQLException {
    String sql = "SELECT rodada_id, inventario_item_id FROM ocorrencias_contagem WHERE id = ?";
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

  private boolean hasRoundItemScope(Connection connection, long roundId) throws SQLException {
    String sql = "SELECT 1 FROM rodada_itens WHERE rodada_id = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private boolean isItemInRoundScope(Connection connection, long roundId, long itemId) throws SQLException {
    String sql = "SELECT 1 FROM rodada_itens WHERE rodada_id = ? AND inventario_item_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      statement.setLong(2, itemId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private CountingProgress calculateProgress(Connection connection, long inventoryId, long roundId)
      throws SQLException {
    boolean hasScope = hasRoundItemScope(connection, roundId);
    int totalItems = 0;

    if (hasScope) {
      String totalScopeSql = "SELECT COUNT(*) FROM rodada_itens WHERE rodada_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(totalScopeSql)) {
        statement.setLong(1, roundId);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next()) totalItems = result.getInt(1);
        }
      }
    } else {
      String totalSql = "SELECT COUNT(*) FROM inventario_itens WHERE inventario_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(totalSql)) {
        statement.setLong(1, inventoryId);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next()) totalItems = result.getInt(1);
        }
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

  private RoundRecord findRoundByNumber(Connection connection, long inventoryId, int numero) throws SQLException {
    String sql = """
        SELECT id, inventario_id, numero, tipo, status, iniciada_por, iniciada_em
        FROM rodadas_contagem
        WHERE inventario_id = ? AND numero = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, inventoryId);
      statement.setInt(2, numero);
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

  private RoundRecord requireRoundForUpdate(Connection connection, long roundId, long inventoryId) throws SQLException {
    String sql = """
        SELECT id, inventario_id, numero, tipo, status, iniciada_por, iniciada_em
        FROM rodadas_contagem
        WHERE id = ? AND inventario_id = ?
        FOR UPDATE
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

  private InventoryHeader requireInventoryForUpdate(Connection connection, long inventoryId, String branchCode)
      throws SQLException {
    String sql = """
        SELECT i.id, i.nome, i.modo, i.status, f.codigo AS filial_codigo
        FROM inventarios i
        JOIN filiais f ON f.id = i.filial_id
        WHERE i.id = ? AND f.codigo = ?
        FOR UPDATE
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

  private List<ItemRecord> loadRoundScopeItems(Connection connection, long roundId, String search)
      throws SQLException {
    String filter = "";
    if (search != null && !search.isBlank()) {
      filter = " AND (LOWER(ii.sku) LIKE ? OR LOWER(ii.descricao_snapshot) LIKE ?)";
    }
    String sql = """
        SELECT ii.id, ii.sku, ii.descricao_snapshot, ii.saldo_snapshot
        FROM inventario_itens ii
        JOIN rodada_itens ri ON ri.inventario_item_id = ii.id
        WHERE ri.rodada_id = ?
        """ + filter + " ORDER BY ii.sku ASC";

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
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
        SELECT id, rodada_id, inventario_item_id, sku, quantidade, localizacao, categoria, tipo_acao,
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
        SELECT id, rodada_id, inventario_item_id, sku, quantidade, localizacao, categoria, tipo_acao,
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

  private Map<Long, AuditItemRecord> loadAuditRecordsForRound(Connection connection, long roundId) throws SQLException {
    String sql = """
        SELECT inventario_item_id, sku, saldo_snapshot, contado, quantidade_fisica, diferenca, estado
        FROM apuracoes_rodada
        WHERE rodada_id = ?
        """;
    Map<Long, AuditItemRecord> map = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, roundId);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          map.put(rs.getLong("inventario_item_id"), new AuditItemRecord(
              rs.getLong("inventario_item_id"),
              rs.getString("sku"),
              rs.getInt("saldo_snapshot"),
              rs.getBoolean("contado"),
              (Integer) rs.getObject("quantidade_fisica"),
              (Integer) rs.getObject("diferenca"),
              rs.getString("estado")
          ));
        }
      }
    }
    return map;
  }

  private OccurrenceRecord mapOccurrence(ResultSet result) throws SQLException {
    Timestamp clientTs = result.getTimestamp("client_timestamp");
    Timestamp serverTs = result.getTimestamp("server_timestamp");
    long refId = result.getLong("referencia_id");
    Long nullableRefId = result.wasNull() ? null : refId;

    String loc = result.getString("localizacao");
    if (loc == null || loc.isBlank()) loc = "GERAL";

    return new OccurrenceRecord(
        result.getLong("id"),
        result.getLong("rodada_id"),
        result.getLong("inventario_item_id"),
        result.getString("sku"),
        result.getInt("quantidade"),
        loc,
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
      String localizacao,
      String categoria,
      String tipoAcao,
      Object clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Long referenciaId
  ) {
    public RecordOccurrenceCommand(
        String sku,
        int quantidade,
        String categoria,
        String tipoAcao,
        Object clientEventId,
        String origem,
        String dispositivo,
        Instant clientTimestamp,
        Long referenciaId
    ) {
      this(sku, quantidade, "GERAL", categoria, tipoAcao, clientEventId, origem, dispositivo, clientTimestamp, referenciaId);
    }
  }

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
      Map<String, Map<String, Integer>> detalhes,
      String estado,
      Long ultimaOcorrenciaId,
      Instant ultimaLeituraEm
  ) {
    public CountingItem(
        long id,
        String sku,
        String descricao,
        Integer saldoSnapshot,
        int quantidadeContada,
        Map<String, Integer> categorias,
        String estado,
        Long ultimaOcorrenciaId,
        Instant ultimaLeituraEm
    ) {
      this(id, sku, descricao, saldoSnapshot, quantidadeContada, categorias,
          Map.of("GERAL", categorias), estado, ultimaOcorrenciaId, ultimaLeituraEm);
    }
  }

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
      String localizacao,
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
      Map<String, Map<String, Integer>> detalhesItem,
      Integer saldoSnapshot,
      CountingProgress progresso
  ) {
    public OccurrenceResult(
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
    ) {
      this(id, rodadaId, inventarioItemId, sku, quantidade, "GERAL", categoria, tipoAcao, operador,
          clientEventId, origem, dispositivo, clientTimestamp, serverTimestamp, referenciaId,
          quantidadeItemProjetada, categoriasItem, Map.of("GERAL", categoriasItem), saldoSnapshot, progresso);
    }
  }

  public record ItemProjection(
      int totalQuantity,
      Map<String, Integer> categoryQuantities,
      Map<String, Map<String, Integer>> locationDetails,
      Long lastOccurrenceId,
      Instant lastOccurrenceTimestamp
  ) {
    public ItemProjection(
        int totalQuantity,
        Map<String, Integer> categoryQuantities,
        Long lastOccurrenceId,
        Instant lastOccurrenceTimestamp
    ) {
      this(totalQuantity, categoryQuantities,
          Map.of("GERAL", categoryQuantities), lastOccurrenceId, lastOccurrenceTimestamp);
    }
  }

  public record RoundAuditResult(
      long inventoryId,
      String inventoryNome,
      String inventoryModo,
      String inventoryStatus,
      long rodadaId,
      int rodadaNumero,
      String rodadaTipo,
      String rodadaStatus,
      Instant finalizadaEm,
      String finalizadaPor,
      boolean encerramentoForcado,
      int pendentesNoFechamento,
      AuditSummary resumo,
      List<AuditItem> itens
  ) {}

  public record AuditSummary(
      int totalItens,
      int conformes,
      int divergentes,
      int naoContados,
      int conformesAposRecontagem,
      int divergenciasConfirmadas,
      int totalFalta,
      int totalSobra
  ) {}

  public record AuditItem(
      long apuracaoId,
      long inventarioItemId,
      String sku,
      String descricao,
      int saldoSnapshot,
      boolean contado,
      Integer quantidadeFisica,
      Integer diferenca,
      String estado,
      Map<String, Object> detalhes
  ) {
    public AuditItem(
        long inventarioItemId,
        String sku,
        String descricao,
        int saldoSnapshot,
        boolean contado,
        Integer quantidadeFisica,
        Integer diferenca,
        String estado,
        Map<String, Object> detalhes
    ) {
      this(0L, inventarioItemId, sku, descricao, saldoSnapshot, contado, quantidadeFisica, diferenca, estado, detalhes);
    }
  }

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
  private record AuditItemRecord(long itemId, String sku, int saldoSnapshot, boolean contado,
                                 Integer quantidadeFisica, Integer diferenca, String estado) {}

  public record OccurrenceRecord(
      long id,
      long rodadaId,
      long inventarioItemId,
      String sku,
      int quantidade,
      String localizacao,
      String categoria,
      String tipoAcao,
      String operador,
      UUID clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Instant serverTimestamp,
      Long referenciaId
  ) {
    public OccurrenceRecord(
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
    ) {
      this(id, rodadaId, inventarioItemId, sku, quantidade, "GERAL", categoria, tipoAcao, operador,
          clientEventId, origem, dispositivo, clientTimestamp, serverTimestamp, referenciaId);
    }
  }

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
  public static final class UncountedItemsConflictException extends CountingException {
    private final int pendentes;
    UncountedItemsConflictException(int pendentes, String message) {
      super(409, message);
      this.pendentes = pendentes;
    }
    public int pendentes() { return pendentes; }
  }
}
