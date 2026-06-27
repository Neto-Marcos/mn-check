package br.com.mncheck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PostgresDatabase {
  private final String jdbcUrl;
  private final String username;
  private final String password;

  public PostgresDatabase(String databaseUrl) {
    if (databaseUrl == null || databaseUrl.isBlank()) {
      throw new DatabaseException("DATABASE_URL não foi configurada.");
    }
    try {
      Class.forName("org.postgresql.Driver");
    } catch (ClassNotFoundException error) {
      throw new DatabaseException("Driver PostgreSQL JDBC não encontrado.", error);
    }
    DatabaseUrlParser.JdbcConfig config;
    try {
      config = DatabaseUrlParser.parse(databaseUrl);
    } catch (DatabaseUrlParser.DatabaseUrlException error) {
      throw new DatabaseException(error.getMessage(), error);
    }
    this.jdbcUrl = config.url();
    this.username = config.username();
    this.password = config.password();
    migrateWithRetry();
  }

  public Connection connect() throws SQLException {
    if (username.isBlank()) return DriverManager.getConnection(jdbcUrl);
    return DriverManager.getConnection(jdbcUrl, username, password);
  }

  public String testConnection() {
    String sql = "SELECT current_database(), current_user";
    try (Connection connection = connect();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      if (!result.next()) throw new SQLException("O PostgreSQL não retornou identificação.");
      return result.getString(1) + "@" + result.getString(2);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível conectar ao PostgreSQL.", error);
    }
  }

  public ImportSummary saveBalanceImport(
      String fileName,
      String importedBy,
      List<BalanceRow> balances,
      int pagesProcessed,
      int totalLinesRead,
      int ignoredLines,
      int duplicateSkus,
      int conflictsFound
  ) {
    String insertImport = """
        INSERT INTO importacoes_saldo
          (nome_arquivo, importado_por, quantidade_skus, paginas_processadas, total_linhas_lidas,
           linhas_ignoradas, skus_duplicados, conflitos_encontrados, atualizado_em)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
        RETURNING id, atualizado_em
        """;
    String insertBalance = """
        INSERT INTO saldos (sku, saldo, importacao_id)
        VALUES (?, ?, ?)
        """;

    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        long importId;
        Instant updatedAt;
        try (PreparedStatement statement = connection.prepareStatement(insertImport)) {
          statement.setString(1, fileName);
          statement.setString(2, safeText(importedBy, "Sistema"));
          statement.setInt(3, balances.size());
          statement.setInt(4, pagesProcessed);
          statement.setInt(5, totalLinesRead);
          statement.setInt(6, ignoredLines);
          statement.setInt(7, duplicateSkus);
          statement.setInt(8, conflictsFound);
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("A importação não retornou um identificador.");
            importId = result.getLong("id");
            updatedAt = result.getTimestamp("atualizado_em").toInstant();
          }
        }

        try (PreparedStatement statement = connection.prepareStatement(insertBalance)) {
          for (BalanceRow balance : balances) {
            statement.setString(1, balance.sku());
            statement.setInt(2, balance.balance());
            statement.setLong(3, importId);
            statement.addBatch();
          }
          statement.executeBatch();
        }
        ImportChanges changes = calculateImportChanges(connection, importId);
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE importacoes_saldo SET itens_alterados = ?, itens_removidos = ? WHERE id = ?")) {
          statement.setInt(1, changes.changedItems());
          statement.setInt(2, changes.removedItems());
          statement.setLong(3, importId);
          statement.executeUpdate();
        }
        synchronizeCurrentInventory(connection, importId, balances);
        connection.commit();
        return new ImportSummary(
            importId, fileName, safeText(importedBy, "Sistema"), balances.size(), updatedAt, pagesProcessed,
            totalLinesRead, ignoredLines, duplicateSkus, conflictsFound,
            changes.changedItems(), changes.removedItems()
        );
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível salvar a importação de saldo.", error);
    }
  }

  private ImportChanges calculateImportChanges(Connection connection, long importId) throws SQLException {
    String previousImport = """
        SELECT id FROM importacoes_saldo
        WHERE id < ?
        ORDER BY atualizado_em DESC, id DESC
        LIMIT 1
        """;
    Long previousId = null;
    try (PreparedStatement statement = connection.prepareStatement(previousImport)) {
      statement.setLong(1, importId);
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) previousId = result.getLong(1);
      }
    }
    if (previousId == null) return new ImportChanges(0, 0);

    String changedSql = """
        SELECT COUNT(*)
        FROM saldos atual
        LEFT JOIN saldos anterior
          ON anterior.importacao_id = ? AND anterior.sku = atual.sku
        WHERE atual.importacao_id = ?
          AND (anterior.id IS NULL OR anterior.saldo <> atual.saldo)
        """;
    String removedSql = """
        SELECT COUNT(*)
        FROM saldos anterior
        LEFT JOIN saldos atual
          ON atual.importacao_id = ? AND atual.sku = anterior.sku
        WHERE anterior.importacao_id = ? AND atual.id IS NULL
        """;
    int changed = scalarCount(connection, changedSql, previousId, importId);
    int removed = scalarCount(connection, removedSql, importId, previousId);
    return new ImportChanges(changed, removed);
  }

  private int scalarCount(Connection connection, String sql, long first, long second) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, first);
      statement.setLong(2, second);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getInt(1) : 0;
      }
    }
  }

  private void synchronizeCurrentInventory(
      Connection connection,
      long importId,
      List<BalanceRow> balances
  ) throws SQLException {
    try (PreparedStatement deactivate = connection.prepareStatement(
        "UPDATE estoque_produtos SET ativo = FALSE, ultima_atualizacao = now() WHERE ativo = TRUE")) {
      deactivate.executeUpdate();
    }
    String upsert = """
        INSERT INTO estoque_produtos
          (sku, saldo_sistema, saldo_contado, saldo_avaria, saldo_outros, ativo, ultima_atualizacao, importacao_id)
        VALUES (?, ?, 0, 0, 0, TRUE, now(), ?)
        ON CONFLICT (sku) DO UPDATE SET
          saldo_sistema = EXCLUDED.saldo_sistema,
          ativo = TRUE,
          ultima_atualizacao = now(),
          importacao_id = EXCLUDED.importacao_id
        """;
    try (PreparedStatement statement = connection.prepareStatement(upsert)) {
      for (BalanceRow balance : balances) {
        statement.setString(1, balance.sku());
        statement.setInt(2, balance.balance());
        statement.setLong(3, importId);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  public long saveCount(String operator, List<CountRow> rows, String status) {
    String insertCount = """
        INSERT INTO contagens (criado_em, operador, importacao_id, status)
        VALUES (now(), ?, (SELECT id FROM importacoes_saldo ORDER BY atualizado_em DESC, id DESC LIMIT 1), ?)
        RETURNING id
        """;
    String insertItem = """
        INSERT INTO itens_contagem
          (contagem_id, sku, saldo_sistema, quantidade_contada, quantidade_avaria, quantidade_outros, diferenca)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        long countId;
        try (PreparedStatement statement = connection.prepareStatement(insertCount)) {
          statement.setString(1, operator);
          statement.setString(2, normalizeCountStatus(status));
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("A contagem não retornou um identificador.");
            countId = result.getLong(1);
          }
        }
        try (PreparedStatement statement = connection.prepareStatement(insertItem)) {
          for (CountRow row : rows) {
            statement.setLong(1, countId);
            statement.setString(2, row.sku());
            statement.setInt(3, row.systemBalance());
            statement.setInt(4, row.countedQuantity());
            statement.setInt(5, row.damagedQuantity());
            statement.setInt(6, row.otherQuantity());
            statement.setInt(7, row.accountedQuantity() - row.systemBalance());
            statement.addBatch();
          }
          statement.executeBatch();
        }
        String updateInventory = """
            UPDATE estoque_produtos
            SET saldo_contado = ?,
                saldo_avaria = ?,
                saldo_outros = ?,
                ultima_contagem_em = now(),
                ultima_atualizacao = now()
            WHERE sku = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(updateInventory)) {
          for (CountRow row : rows) {
            statement.setInt(1, row.countedQuantity());
            statement.setInt(2, row.damagedQuantity());
            statement.setInt(3, row.otherQuantity());
            statement.setString(4, row.sku());
            statement.addBatch();
          }
          statement.executeBatch();
        }
        connection.commit();
        return countId;
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível salvar a contagem.", error);
    }
  }

  public long saveCount(String operator, List<CountRow> rows) {
    return saveCount(operator, rows, "FINALIZADA");
  }

  public CountCycle loadLatestCountCycle() {
    String sql = """
        SELECT id, criado_em, operador, status
        FROM contagens
        ORDER BY criado_em DESC, id DESC
        LIMIT 1
        """;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
      if (!result.next()) return CountCycle.open();
      return new CountCycle(
          result.getLong("id"),
          result.getString("status"),
          result.getString("operador"),
          result.getTimestamp("criado_em").toInstant()
      );
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar o ciclo da contagem.", error);
    }
  }

  private static String normalizeCountStatus(String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    if (List.of("ABERTA", "EM_ANDAMENTO", "FINALIZADA", "CANCELADA").contains(normalized)) return normalized;
    return "EM_ANDAMENTO";
  }

  public BalanceSnapshot loadLatestBalances() {
    ImportSummary latest = latestImport();
    if (latest == null) return BalanceSnapshot.empty();

    List<CountRow> rows = new ArrayList<>();
    String sql = """
        SELECT sku, saldo_sistema, saldo_contado, saldo_avaria, saldo_outros
        FROM estoque_produtos
        WHERE ativo = TRUE
        ORDER BY sku
        """;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String sku = result.getString("sku");
          rows.add(new CountRow(
              sku,
              result.getInt("saldo_sistema"),
              result.getInt("saldo_contado"),
              result.getInt("saldo_avaria"),
              result.getInt("saldo_outros")
          ));
        }
      }
      return new BalanceSnapshot(latest, rows);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar os saldos.", error);
    }
  }

  public ImportSummary saveManualBalanceProduct(
      String sku,
      int systemBalance,
      int countedQuantity,
      int damagedQuantity,
      int otherQuantity,
      String operator
  ) {
    String latestImportSql = "SELECT id FROM importacoes_saldo ORDER BY atualizado_em DESC, id DESC LIMIT 1";
    String createImportSql = """
        INSERT INTO importacoes_saldo
          (nome_arquivo, importado_por, quantidade_skus, atualizado_em, itens_alterados)
        VALUES ('Ajuste manual de produto', ?, 1, now(), 1)
        RETURNING id, atualizado_em
        """;
    String insertBalanceSql = """
        INSERT INTO saldos (sku, saldo, importacao_id)
        VALUES (?, ?, ?)
        ON CONFLICT (importacao_id, sku) DO UPDATE SET saldo = EXCLUDED.saldo
        """;
    String upsertInventorySql = """
        INSERT INTO estoque_produtos
          (sku, saldo_sistema, saldo_contado, saldo_avaria, saldo_outros, ativo, ultima_atualizacao, ultima_contagem_em, importacao_id)
        VALUES (?, ?, ?, ?, ?, TRUE, now(), now(), ?)
        ON CONFLICT (sku) DO UPDATE SET
          saldo_sistema = EXCLUDED.saldo_sistema,
          saldo_contado = EXCLUDED.saldo_contado,
          saldo_avaria = EXCLUDED.saldo_avaria,
          saldo_outros = EXCLUDED.saldo_outros,
          ativo = TRUE,
          ultima_atualizacao = now(),
          ultima_contagem_em = now(),
          importacao_id = EXCLUDED.importacao_id
        """;
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        long importId;
        Instant updatedAt;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(latestImportSql)) {
          if (result.next()) {
            importId = result.getLong(1);
            updatedAt = latestImportTimestamp(connection, importId);
          } else {
            try (PreparedStatement insertImport = connection.prepareStatement(createImportSql)) {
              insertImport.setString(1, safeText(operator, "Sistema"));
              try (ResultSet created = insertImport.executeQuery()) {
                if (!created.next()) throw new SQLException("A importação manual não retornou identificador.");
                importId = created.getLong("id");
                updatedAt = created.getTimestamp("atualizado_em").toInstant();
              }
            }
          }
        }
        try (PreparedStatement statement = connection.prepareStatement(insertBalanceSql)) {
          statement.setString(1, sku);
          statement.setInt(2, systemBalance);
          statement.setLong(3, importId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(upsertInventorySql)) {
          statement.setString(1, sku);
          statement.setInt(2, systemBalance);
          statement.setInt(3, countedQuantity);
          statement.setInt(4, damagedQuantity);
          statement.setInt(5, otherQuantity);
          statement.setLong(6, importId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE importacoes_saldo
            SET quantidade_skus = (SELECT COUNT(*) FROM saldos WHERE importacao_id = ?),
                itens_alterados = itens_alterados + 1
            WHERE id = ?
            """)) {
          statement.setLong(1, importId);
          statement.setLong(2, importId);
          statement.executeUpdate();
        }
        connection.commit();
        return latestImportById(importId, updatedAt, safeText(operator, "Sistema"));
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível adicionar o produto manualmente.", error);
    }
  }

  private Instant latestImportTimestamp(Connection connection, long importId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT atualizado_em FROM importacoes_saldo WHERE id = ?")) {
      statement.setLong(1, importId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("Importação não encontrada.");
        return result.getTimestamp("atualizado_em").toInstant();
      }
    }
  }

  private ImportSummary latestImportById(long importId, Instant fallbackUpdatedAt, String fallbackOperator)
      throws SQLException {
    String sql = """
        SELECT nome_arquivo, importado_por, quantidade_skus, atualizado_em, paginas_processadas,
               total_linhas_lidas, linhas_ignoradas, skus_duplicados, conflitos_encontrados,
               itens_alterados, itens_removidos
        FROM importacoes_saldo WHERE id = ?
        """;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, importId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return new ImportSummary(importId, "Ajuste manual de produto", fallbackOperator, 1, fallbackUpdatedAt,
              0, 0, 0, 0, 0, 1, 0);
        }
        return new ImportSummary(
            importId,
            result.getString("nome_arquivo"),
            result.getString("importado_por"),
            result.getInt("quantidade_skus"),
            result.getTimestamp("atualizado_em").toInstant(),
            result.getInt("paginas_processadas"),
            result.getInt("total_linhas_lidas"),
            result.getInt("linhas_ignoradas"),
            result.getInt("skus_duplicados"),
            result.getInt("conflitos_encontrados"),
            result.getInt("itens_alterados"),
            result.getInt("itens_removidos")
        );
      }
    }
  }

  public List<HistoryEntry> loadHistory() {
    List<HistoryEntry> history = new ArrayList<>();
    String imports = """
        SELECT id, nome_arquivo, importado_por, quantidade_skus, atualizado_em
        FROM importacoes_saldo ORDER BY atualizado_em DESC
        """;
    String counts = """
        SELECT c.id, c.operador, c.criado_em, COUNT(i.id) AS itens
        FROM contagens c
        LEFT JOIN itens_contagem i ON i.contagem_id = c.id
        GROUP BY c.id, c.operador, c.criado_em
        ORDER BY c.criado_em DESC
        """;
    try (Connection connection = connect();
         Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery(imports)) {
        while (result.next()) {
          history.add(new HistoryEntry(
              result.getTimestamp("atualizado_em").toInstant(),
              result.getString("importado_por"),
              "count_upload",
              "Saldo importado de " + result.getString("nome_arquivo")
                  + " com " + result.getInt("quantidade_skus") + " SKUs"
          ));
        }
      }
      try (ResultSet result = statement.executeQuery(counts)) {
        while (result.next()) {
          history.add(new HistoryEntry(
              result.getTimestamp("criado_em").toInstant(),
              result.getString("operador"),
              "update_counts",
              "Contagem " + result.getLong("id") + " salva com " + result.getInt("itens") + " SKUs"
          ));
        }
      }
      history.sort((left, right) -> right.at().compareTo(left.at()));
      return history;
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar o histórico.", error);
    }
  }

  public List<Map<String, Object>> loadBalanceHistory(int limit) {
    String sql = """
        SELECT i.id, i.nome_arquivo, i.importado_por, i.quantidade_skus, i.atualizado_em,
               i.itens_alterados, i.itens_removidos
        FROM importacoes_saldo i
        ORDER BY i.atualizado_em DESC, i.id DESC
        LIMIT ?
        """;
    List<Map<String, Object>> history = new ArrayList<>();
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, Math.max(1, Math.min(limit, 100)));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          history.add(Map.of(
              "id", result.getLong("id"),
              "fileName", result.getString("nome_arquivo"),
              "importedBy", result.getString("importado_por"),
              "skuCount", result.getInt("quantidade_skus"),
              "changedItems", result.getInt("itens_alterados"),
              "removedItems", result.getInt("itens_removidos"),
              "updatedAt", result.getTimestamp("atualizado_em").toInstant().toString()
          ));
        }
      }
      return history;
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar o histórico de saldos.", error);
    }
  }

  public Map<String, Object> loadInventoryMetrics() {
    String sql = """
        SELECT
          COUNT(*) FILTER (WHERE ativo) AS ativos,
          COUNT(*) FILTER (WHERE NOT ativo) AS inativos,
          COUNT(*) FILTER (WHERE ativo AND (saldo_contado + saldo_avaria + saldo_outros) <> saldo_sistema) AS divergentes
        FROM estoque_produtos
        """;
    try (Connection connection = connect();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      if (!result.next()) return Map.of("active", 0, "inactive", 0, "divergent", 0);
      return Map.of(
          "active", result.getInt("ativos"),
          "inactive", result.getInt("inativos"),
          "divergent", result.getInt("divergentes")
      );
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar os indicadores de estoque.", error);
    }
  }

  public ScanHistoryEntry saveScanHistory(
      String mapId,
      String operator,
      String expectedCode,
      String scannedCode,
      boolean approved,
      String reason,
      String source
  ) {
    String sql = """
        INSERT INTO historico_scanner
          (mapa_id, operador, codigo_esperado, codigo_lido, aprovado, motivo, origem, criado_em)
        VALUES (?, ?, ?, ?, ?, ?, ?, now())
        RETURNING id, criado_em
        """;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, safeText(mapId, "sem-mapa"));
      statement.setString(2, safeText(operator, "Operador"));
      statement.setString(3, expectedCode);
      statement.setString(4, scannedCode);
      statement.setBoolean(5, approved);
      statement.setString(6, reason);
      statement.setString(7, source);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("O histórico não retornou identificador.");
        return new ScanHistoryEntry(
            result.getLong("id"),
            safeText(mapId, "sem-mapa"),
            safeText(operator, "Operador"),
            expectedCode,
            scannedCode,
            approved,
            reason,
            source,
            result.getTimestamp("criado_em").toInstant()
        );
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível salvar o histórico do scanner.", error);
    }
  }

  public List<ScanHistoryEntry> loadScanHistory(String mapId, int limit) {
    String sql = """
        SELECT id, mapa_id, operador, codigo_esperado, codigo_lido,
               aprovado, motivo, origem, criado_em
        FROM historico_scanner
        WHERE mapa_id = ?
        ORDER BY criado_em DESC, id DESC
        LIMIT ?
        """;
    List<ScanHistoryEntry> history = new ArrayList<>();
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, mapId);
      statement.setInt(2, limit);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          history.add(new ScanHistoryEntry(
              result.getLong("id"),
              result.getString("mapa_id"),
              result.getString("operador"),
              result.getString("codigo_esperado"),
              result.getString("codigo_lido"),
              result.getBoolean("aprovado"),
              result.getString("motivo"),
              result.getString("origem"),
              result.getTimestamp("criado_em").toInstant()
          ));
        }
      }
      return history;
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar o histórico do scanner.", error);
    }
  }

  public ConferenceSession saveConferenceProgress(
      String mapId,
      String operator,
      String sku,
      int checkedQuantity,
      int expectedQuantity
  ) {
    String sessionSql = """
        INSERT INTO conferencias
          (mapa_id, operador, status, iniciado_em, atualizado_em)
        VALUES (?, ?, 'EM_ANDAMENTO', now(), now())
        ON CONFLICT (mapa_id) DO UPDATE SET
          operador = EXCLUDED.operador,
          status = 'EM_ANDAMENTO',
          atualizado_em = now()
        RETURNING id, mapa_id, operador, status, iniciado_em, atualizado_em, finalizado_em
        """;
    String itemSql = """
        INSERT INTO itens_conferencia
          (conferencia_id, sku, quantidade_esperada, quantidade_conferida, atualizado_em)
        VALUES (?, ?, ?, ?, now())
        ON CONFLICT (conferencia_id, sku) DO UPDATE SET
          quantidade_esperada = EXCLUDED.quantidade_esperada,
          quantidade_conferida = EXCLUDED.quantidade_conferida,
          atualizado_em = now()
        """;
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        ConferenceSession session;
        try (PreparedStatement statement = connection.prepareStatement(sessionSql)) {
          statement.setString(1, mapId);
          statement.setString(2, safeText(operator, "Operador"));
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("A conferência não retornou identificador.");
            session = conferenceSession(result, List.of());
          }
        }
        try (PreparedStatement statement = connection.prepareStatement(itemSql)) {
          statement.setLong(1, session.id());
          statement.setString(2, sku);
          statement.setInt(3, expectedQuantity);
          statement.setInt(4, checkedQuantity);
          statement.executeUpdate();
        }
        connection.commit();
        return loadConferenceSession(mapId);
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível salvar o progresso da conferência.", error);
    }
  }

  public ConferenceSession changeConferenceStatus(String mapId, String operator, String status) {
    if (!List.of("EM_ANDAMENTO", "PAUSADA", "FINALIZADA", "CANCELADA").contains(status)) {
      throw new DatabaseException("Status de conferência inválido.");
    }
    String sql = """
        INSERT INTO conferencias
          (mapa_id, operador, status, iniciado_em, atualizado_em, finalizado_em)
        VALUES (?, ?, ?, now(), now(), CASE WHEN ? IN ('FINALIZADA', 'CANCELADA') THEN now() ELSE NULL END)
        ON CONFLICT (mapa_id) DO UPDATE SET
          operador = EXCLUDED.operador,
          status = EXCLUDED.status,
          atualizado_em = now(),
          finalizado_em = CASE
            WHEN EXCLUDED.status IN ('FINALIZADA', 'CANCELADA') THEN now()
            ELSE NULL
          END
        """;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, mapId);
      statement.setString(2, safeText(operator, "Operador"));
      statement.setString(3, status);
      statement.setString(4, status);
      statement.executeUpdate();
      return loadConferenceSession(mapId);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível atualizar a conferência.", error);
    }
  }

  public ConferenceSession cancelConferenceAndClear(String mapId, String operator) {
    ConferenceSession session = changeConferenceStatus(mapId, operator, "CANCELADA");
    if (session == null) return null;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM itens_conferencia WHERE conferencia_id = ?")) {
      statement.setLong(1, session.id());
      statement.executeUpdate();
      return loadConferenceSession(mapId);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível limpar o progresso da conferência.", error);
    }
  }

  public ConferenceSession loadConferenceSession(String mapId) {
    String sql = """
        SELECT id, mapa_id, operador, status, iniciado_em, atualizado_em, finalizado_em
        FROM conferencias WHERE mapa_id = ?
        """;
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, mapId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        long id = result.getLong("id");
        return conferenceSession(result, loadConferenceItems(connection, id));
      }
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar a conferência.", error);
    }
  }

  public Map<String, ConferenceSession> loadConferenceSessions() {
    String sql = """
        SELECT id, mapa_id, operador, status, iniciado_em, atualizado_em, finalizado_em
        FROM conferencias
        WHERE status IN ('EM_ANDAMENTO', 'PAUSADA')
        ORDER BY atualizado_em DESC
        """;
    Map<String, ConferenceSession> sessions = new LinkedHashMap<>();
    try (Connection connection = connect();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        long id = result.getLong("id");
        ConferenceSession session = conferenceSession(result, loadConferenceItems(connection, id));
        sessions.put(session.mapId(), session);
      }
      return sessions;
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar conferências ativas.", error);
    }
  }

  private List<ConferenceItem> loadConferenceItems(Connection connection, long conferenceId) throws SQLException {
    String sql = """
        SELECT sku, quantidade_esperada, quantidade_conferida, atualizado_em
        FROM itens_conferencia WHERE conferencia_id = ? ORDER BY sku
        """;
    List<ConferenceItem> items = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, conferenceId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          items.add(new ConferenceItem(
              result.getString("sku"),
              result.getInt("quantidade_esperada"),
              result.getInt("quantidade_conferida"),
              result.getTimestamp("atualizado_em").toInstant()
          ));
        }
      }
    }
    return items;
  }

  private ConferenceSession conferenceSession(ResultSet result, List<ConferenceItem> items) throws SQLException {
    Timestamp finished = result.getTimestamp("finalizado_em");
    return new ConferenceSession(
        result.getLong("id"),
        result.getString("mapa_id"),
        result.getString("operador"),
        result.getString("status"),
        result.getTimestamp("iniciado_em").toInstant(),
        result.getTimestamp("atualizado_em").toInstant(),
        finished == null ? null : finished.toInstant(),
        items
    );
  }

  private ImportSummary latestImport() {
    String sql = """
        SELECT id, nome_arquivo, importado_por, quantidade_skus, atualizado_em,
               paginas_processadas, total_linhas_lidas, linhas_ignoradas,
               skus_duplicados, conflitos_encontrados, itens_alterados, itens_removidos
        FROM importacoes_saldo
        ORDER BY atualizado_em DESC, id DESC
        LIMIT 1
        """;
    try (Connection connection = connect();
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      if (!result.next()) return null;
      return new ImportSummary(
          result.getLong("id"),
          result.getString("nome_arquivo"),
          result.getString("importado_por"),
          result.getInt("quantidade_skus"),
          result.getTimestamp("atualizado_em").toInstant(),
          result.getInt("paginas_processadas"),
          result.getInt("total_linhas_lidas"),
          result.getInt("linhas_ignoradas"),
          result.getInt("skus_duplicados"),
          result.getInt("conflitos_encontrados"),
          result.getInt("itens_alterados"),
          result.getInt("itens_removidos")
      );
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar a última importação.", error);
    }
  }

  private Map<String, Integer> loadLatestCountQuantities(Instant importedAt) throws SQLException {
    String sql = """
        SELECT i.sku, i.quantidade_contada
        FROM itens_contagem i
        WHERE i.contagem_id = (
          SELECT id FROM contagens
          WHERE criado_em >= ?
          ORDER BY criado_em DESC, id DESC
          LIMIT 1
        )
        """;
    Map<String, Integer> quantities = new LinkedHashMap<>();
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(importedAt));
      try (ResultSet result = statement.executeQuery()) {
      while (result.next()) quantities.put(result.getString(1), result.getInt(2));
      }
    }
    return quantities;
  }

  private void migrateWithRetry() {
    DatabaseException lastError = null;
    for (int attempt = 1; attempt <= 6; attempt++) {
      try {
        migrate();
        if (attempt > 1) {
          System.out.println("PostgreSQL conectado após " + attempt + " tentativas.");
        }
        return;
      } catch (DatabaseException error) {
        lastError = error;
        System.err.println(
            "PostgreSQL indisponível na tentativa " + attempt + "/6: "
                + rootMessage(error)
        );
        if (attempt < 6) sleepBeforeRetry(attempt);
      }
    }
    throw lastError;
  }

  private void migrate() {
    String[] statements = {
        """
        CREATE TABLE IF NOT EXISTS importacoes_saldo (
          id BIGSERIAL PRIMARY KEY,
          nome_arquivo TEXT NOT NULL,
          importado_por TEXT NOT NULL DEFAULT 'Sistema',
          quantidade_skus INTEGER NOT NULL CHECK (quantidade_skus >= 0),
          atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
          paginas_processadas INTEGER NOT NULL DEFAULT 0,
          total_linhas_lidas INTEGER NOT NULL DEFAULT 0,
          linhas_ignoradas INTEGER NOT NULL DEFAULT 0,
          skus_duplicados INTEGER NOT NULL DEFAULT 0,
          conflitos_encontrados INTEGER NOT NULL DEFAULT 0,
          itens_alterados INTEGER NOT NULL DEFAULT 0,
          itens_removidos INTEGER NOT NULL DEFAULT 0
        )
        """,
        "ALTER TABLE importacoes_saldo ADD COLUMN IF NOT EXISTS importado_por TEXT NOT NULL DEFAULT 'Sistema'",
        "ALTER TABLE importacoes_saldo ADD COLUMN IF NOT EXISTS total_linhas_lidas INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE importacoes_saldo ADD COLUMN IF NOT EXISTS itens_alterados INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE importacoes_saldo ADD COLUMN IF NOT EXISTS itens_removidos INTEGER NOT NULL DEFAULT 0",
        """
        CREATE TABLE IF NOT EXISTS saldos (
          id BIGSERIAL PRIMARY KEY,
          sku VARCHAR(64) NOT NULL,
          saldo INTEGER NOT NULL CHECK (saldo >= 0),
          importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id) ON DELETE CASCADE,
          UNIQUE (importacao_id, sku)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS contagens (
          id BIGSERIAL PRIMARY KEY,
          criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
          operador TEXT NOT NULL,
          importacao_id BIGINT REFERENCES importacoes_saldo(id),
          status VARCHAR(24) NOT NULL DEFAULT 'ABERTA'
        )
        """,
        "ALTER TABLE contagens ADD COLUMN IF NOT EXISTS importacao_id BIGINT REFERENCES importacoes_saldo(id)",
        "ALTER TABLE contagens ADD COLUMN IF NOT EXISTS status VARCHAR(24) NOT NULL DEFAULT 'ABERTA'",
        "ALTER TABLE contagens ALTER COLUMN status SET DEFAULT 'ABERTA'",
        """
        CREATE TABLE IF NOT EXISTS itens_contagem (
          id BIGSERIAL PRIMARY KEY,
          contagem_id BIGINT NOT NULL REFERENCES contagens(id) ON DELETE CASCADE,
          sku VARCHAR(64) NOT NULL,
          saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
          quantidade_contada INTEGER NOT NULL CHECK (quantidade_contada >= 0),
          quantidade_avaria INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_avaria >= 0),
          quantidade_outros INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_outros >= 0),
          diferenca INTEGER NOT NULL,
          UNIQUE (contagem_id, sku)
        )
        """,
        "ALTER TABLE itens_contagem ADD COLUMN IF NOT EXISTS quantidade_avaria INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_avaria >= 0)",
        "ALTER TABLE itens_contagem ADD COLUMN IF NOT EXISTS quantidade_outros INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_outros >= 0)",
        """
        CREATE TABLE IF NOT EXISTS historico_scanner (
          id BIGSERIAL PRIMARY KEY,
          mapa_id TEXT NOT NULL,
          operador TEXT NOT NULL,
          codigo_esperado VARCHAR(16) NOT NULL,
          codigo_lido VARCHAR(16) NOT NULL,
          aprovado BOOLEAN NOT NULL,
          motivo TEXT NOT NULL,
          origem VARCHAR(16) NOT NULL,
          criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS estoque_produtos (
          sku VARCHAR(64) PRIMARY KEY,
          saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
          saldo_contado INTEGER NOT NULL DEFAULT 0 CHECK (saldo_contado >= 0),
          saldo_avaria INTEGER NOT NULL DEFAULT 0 CHECK (saldo_avaria >= 0),
          saldo_outros INTEGER NOT NULL DEFAULT 0 CHECK (saldo_outros >= 0),
          ativo BOOLEAN NOT NULL DEFAULT TRUE,
          ultima_atualizacao TIMESTAMPTZ NOT NULL DEFAULT now(),
          ultima_contagem_em TIMESTAMPTZ,
          importacao_id BIGINT NOT NULL REFERENCES importacoes_saldo(id)
        )
        """,
        "ALTER TABLE estoque_produtos ADD COLUMN IF NOT EXISTS saldo_avaria INTEGER NOT NULL DEFAULT 0 CHECK (saldo_avaria >= 0)",
        "ALTER TABLE estoque_produtos ADD COLUMN IF NOT EXISTS saldo_outros INTEGER NOT NULL DEFAULT 0 CHECK (saldo_outros >= 0)",
        """
        CREATE TABLE IF NOT EXISTS conferencias (
          id BIGSERIAL PRIMARY KEY,
          mapa_id TEXT NOT NULL UNIQUE,
          operador TEXT NOT NULL,
          status VARCHAR(24) NOT NULL CHECK (status IN ('EM_ANDAMENTO', 'PAUSADA', 'FINALIZADA', 'CANCELADA')),
          iniciado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
          atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
          finalizado_em TIMESTAMPTZ
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS itens_conferencia (
          id BIGSERIAL PRIMARY KEY,
          conferencia_id BIGINT NOT NULL REFERENCES conferencias(id) ON DELETE CASCADE,
          sku VARCHAR(64) NOT NULL,
          quantidade_esperada INTEGER NOT NULL CHECK (quantidade_esperada >= 0),
          quantidade_conferida INTEGER NOT NULL CHECK (quantidade_conferida >= 0),
          atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
          UNIQUE (conferencia_id, sku)
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_saldos_importacao ON saldos(importacao_id)",
        "CREATE INDEX IF NOT EXISTS idx_contagens_criado_em ON contagens(criado_em DESC)",
        "CREATE INDEX IF NOT EXISTS idx_itens_contagem_contagem ON itens_contagem(contagem_id)",
        "CREATE INDEX IF NOT EXISTS idx_historico_scanner_mapa ON historico_scanner(mapa_id, criado_em DESC)"
        ,"CREATE INDEX IF NOT EXISTS idx_estoque_produtos_ativo ON estoque_produtos(ativo, sku)"
        ,"CREATE INDEX IF NOT EXISTS idx_conferencias_status ON conferencias(status, atualizado_em DESC)"
    };
    try (Connection connection = connect(); Statement statement = connection.createStatement()) {
      for (String sql : statements) statement.execute(sql);
      statement.execute("""
          INSERT INTO estoque_produtos
            (sku, saldo_sistema, saldo_contado, saldo_avaria, saldo_outros, ativo, ultima_atualizacao, ultima_contagem_em, importacao_id)
          SELECT
            s.sku,
            s.saldo,
            COALESCE((
              SELECT ic.quantidade_contada
              FROM itens_contagem ic
              JOIN contagens c ON c.id = ic.contagem_id
              WHERE ic.sku = s.sku
              ORDER BY c.criado_em DESC, c.id DESC
              LIMIT 1
            ), 0),
            COALESCE((
              SELECT ic.quantidade_avaria
              FROM itens_contagem ic
              JOIN contagens c ON c.id = ic.contagem_id
              WHERE ic.sku = s.sku
              ORDER BY c.criado_em DESC, c.id DESC
              LIMIT 1
            ), 0),
            COALESCE((
              SELECT ic.quantidade_outros
              FROM itens_contagem ic
              JOIN contagens c ON c.id = ic.contagem_id
              WHERE ic.sku = s.sku
              ORDER BY c.criado_em DESC, c.id DESC
              LIMIT 1
            ), 0),
            TRUE,
            now(),
            NULL,
            s.importacao_id
          FROM saldos s
          WHERE s.importacao_id = (
            SELECT id FROM importacoes_saldo ORDER BY atualizado_em DESC, id DESC LIMIT 1
          )
          ON CONFLICT (sku) DO NOTHING
          """);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível criar as tabelas do PostgreSQL.", error);
    }
  }

  private void sleepBeforeRetry(int attempt) {
    try {
      Thread.sleep(Math.min(attempt * 2_000L, 10_000L));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new DatabaseException("Inicialização do PostgreSQL interrompida.", error);
    }
  }

  private String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) current = current.getCause();
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  public record BalanceRow(String sku, int balance) {}
  public record CountRow(
      String sku,
      int systemBalance,
      int countedQuantity,
      int damagedQuantity,
      int otherQuantity
  ) {
    int accountedQuantity() {
      return countedQuantity + damagedQuantity + otherQuantity;
    }
  }
  public record ImportSummary(
      long id,
      String fileName,
      String importedBy,
      int skuCount,
      Instant updatedAt,
      int pagesProcessed,
      int totalLinesRead,
      int ignoredLines,
      int duplicateSkus,
      int conflictsFound,
      int changedItems,
      int removedItems
  ) {}
  private record ImportChanges(int changedItems, int removedItems) {}
  public record BalanceSnapshot(ImportSummary importSummary, List<CountRow> rows) {
    static BalanceSnapshot empty() {
      return new BalanceSnapshot(null, List.of());
    }
  }
  public record CountCycle(long id, String status, String operator, Instant createdAt) {
    static CountCycle open() {
      return new CountCycle(0, "ABERTA", "", null);
    }

    public Map<String, Object> toMap() {
      return Map.of(
          "id", id,
          "status", status,
          "operator", operator == null ? "" : operator,
          "createdAt", createdAt == null ? "" : createdAt.toString()
      );
    }
  }
  public record HistoryEntry(Instant at, String operator, String action, String description) {}
  public record ScanHistoryEntry(
      long id,
      String mapId,
      String operator,
      String expectedCode,
      String scannedCode,
      boolean approved,
      String reason,
      String source,
      Instant createdAt
  ) {}
  public record ConferenceItem(
      String sku,
      int expectedQuantity,
      int checkedQuantity,
      Instant updatedAt
  ) {}
  public record ConferenceSession(
      long id,
      String mapId,
      String operator,
      String status,
      Instant startedAt,
      Instant updatedAt,
      Instant finishedAt,
      List<ConferenceItem> items
  ) {
    public Map<String, Object> toMap() {
      int expected = items.stream().mapToInt(ConferenceItem::expectedQuantity).sum();
      int checked = items.stream().mapToInt(ConferenceItem::checkedQuantity).sum();
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", id);
      map.put("mapId", mapId);
      map.put("operator", operator);
      map.put("status", status);
      map.put("startedAt", startedAt.toString());
      map.put("updatedAt", updatedAt.toString());
      map.put("finishedAt", finishedAt == null ? "" : finishedAt.toString());
      map.put("expectedQuantity", expected);
      map.put("checkedQuantity", checked);
      map.put("progress", expected == 0 ? 0 : Math.round((checked * 100f) / expected));
      map.put("items", items.stream().map(item -> Map.of(
          "sku", item.sku(),
          "expectedQuantity", item.expectedQuantity(),
          "checkedQuantity", item.checkedQuantity(),
          "updatedAt", item.updatedAt().toString()
      )).toList());
      return map;
    }
  }

  public static final class DatabaseException extends RuntimeException {
    DatabaseException(String message) {
      super(message);
    }

    DatabaseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static String safeText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
