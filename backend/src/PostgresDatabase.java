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
      List<BalanceRow> balances,
      int pagesProcessed,
      int ignoredLines,
      int duplicateSkus,
      int conflictsFound
  ) {
    String insertImport = """
        INSERT INTO importacoes_saldo
          (nome_arquivo, quantidade_skus, paginas_processadas, linhas_ignoradas,
           skus_duplicados, conflitos_encontrados, atualizado_em)
        VALUES (?, ?, ?, ?, ?, ?, now())
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
          statement.setInt(2, balances.size());
          statement.setInt(3, pagesProcessed);
          statement.setInt(4, ignoredLines);
          statement.setInt(5, duplicateSkus);
          statement.setInt(6, conflictsFound);
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
        connection.commit();
        return new ImportSummary(
            importId, fileName, balances.size(), updatedAt, pagesProcessed,
            ignoredLines, duplicateSkus, conflictsFound
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

  public long saveCount(String operator, List<CountRow> rows) {
    String insertCount = "INSERT INTO contagens (criado_em, operador) VALUES (now(), ?) RETURNING id";
    String insertItem = """
        INSERT INTO itens_contagem
          (contagem_id, sku, saldo_sistema, quantidade_contada, diferenca)
        VALUES (?, ?, ?, ?, ?)
        """;
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try {
        long countId;
        try (PreparedStatement statement = connection.prepareStatement(insertCount)) {
          statement.setString(1, operator);
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
            statement.setInt(5, row.countedQuantity() - row.systemBalance());
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

  public BalanceSnapshot loadLatestBalances() {
    ImportSummary latest = latestImport();
    if (latest == null) return BalanceSnapshot.empty();

    List<CountRow> rows = new ArrayList<>();
    String sql = "SELECT sku, saldo FROM saldos WHERE importacao_id = ? ORDER BY sku";
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      Map<String, Integer> latestCount = loadLatestCountQuantities(latest.updatedAt());
      statement.setLong(1, latest.id());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String sku = result.getString("sku");
          rows.add(new CountRow(sku, result.getInt("saldo"), latestCount.getOrDefault(sku, 0)));
        }
      }
      return new BalanceSnapshot(latest, rows);
    } catch (SQLException error) {
      throw new DatabaseException("Não foi possível carregar os saldos.", error);
    }
  }

  public List<HistoryEntry> loadHistory() {
    List<HistoryEntry> history = new ArrayList<>();
    String imports = """
        SELECT id, nome_arquivo, quantidade_skus, atualizado_em
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
              "Sistema",
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

  private ImportSummary latestImport() {
    String sql = """
        SELECT id, nome_arquivo, quantidade_skus, atualizado_em,
               paginas_processadas, linhas_ignoradas, skus_duplicados, conflitos_encontrados
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
          result.getInt("quantidade_skus"),
          result.getTimestamp("atualizado_em").toInstant(),
          result.getInt("paginas_processadas"),
          result.getInt("linhas_ignoradas"),
          result.getInt("skus_duplicados"),
          result.getInt("conflitos_encontrados")
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
          quantidade_skus INTEGER NOT NULL CHECK (quantidade_skus >= 0),
          atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
          paginas_processadas INTEGER NOT NULL DEFAULT 0,
          linhas_ignoradas INTEGER NOT NULL DEFAULT 0,
          skus_duplicados INTEGER NOT NULL DEFAULT 0,
          conflitos_encontrados INTEGER NOT NULL DEFAULT 0
        )
        """,
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
          operador TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS itens_contagem (
          id BIGSERIAL PRIMARY KEY,
          contagem_id BIGINT NOT NULL REFERENCES contagens(id) ON DELETE CASCADE,
          sku VARCHAR(64) NOT NULL,
          saldo_sistema INTEGER NOT NULL CHECK (saldo_sistema >= 0),
          quantidade_contada INTEGER NOT NULL CHECK (quantidade_contada >= 0),
          diferenca INTEGER NOT NULL,
          UNIQUE (contagem_id, sku)
        )
        """,
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
        "CREATE INDEX IF NOT EXISTS idx_saldos_importacao ON saldos(importacao_id)",
        "CREATE INDEX IF NOT EXISTS idx_contagens_criado_em ON contagens(criado_em DESC)",
        "CREATE INDEX IF NOT EXISTS idx_itens_contagem_contagem ON itens_contagem(contagem_id)",
        "CREATE INDEX IF NOT EXISTS idx_historico_scanner_mapa ON historico_scanner(mapa_id, criado_em DESC)"
    };
    try (Connection connection = connect(); Statement statement = connection.createStatement()) {
      for (String sql : statements) statement.execute(sql);
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
  public record CountRow(String sku, int systemBalance, int countedQuantity) {}
  public record ImportSummary(
      long id,
      String fileName,
      int skuCount,
      Instant updatedAt,
      int pagesProcessed,
      int ignoredLines,
      int duplicateSkus,
      int conflictsFound
  ) {}
  public record BalanceSnapshot(ImportSummary importSummary, List<CountRow> rows) {
    static BalanceSnapshot empty() {
      return new BalanceSnapshot(null, List.of());
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
