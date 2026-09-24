package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

class FlywayMigrationTest {
  @Test
  void migratesEmptyAndExistingSchemasWithoutLosingLegacyData() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String emptySchema = schemaName("empty");
    String existingSchema = schemaName("existing");

    try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + emptySchema);
      statement.execute("CREATE SCHEMA " + existingSchema);
      statement.execute("CREATE TABLE " + existingSchema + ".legacy_guard (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
      statement.execute("INSERT INTO " + existingSchema + ".legacy_guard VALUES (1, 'preservar')");
    }

    try {
      Flyway emptyFlyway = flyway(config, emptySchema);
      MigrateResult emptyResult = emptyFlyway.migrate();
      assertEquals(1, emptyResult.migrationsExecuted);
      assertBranchCreatedOnce(config, emptySchema);

      Flyway existingFlyway = flyway(config, existingSchema);
      MigrateResult existingResult = existingFlyway.migrate();
      assertEquals(1, existingResult.migrationsExecuted,
          "schema existente deve receber V1 depois do baseline 0");
      assertBranchCreatedOnce(config, existingSchema);
      assertEquals("preservar", scalar(config,
          "SELECT value FROM " + existingSchema + ".legacy_guard WHERE id = 1"));

      MigrateResult repeated = existingFlyway.migrate();
      assertEquals(0, repeated.migrationsExecuted);
      assertBranchCreatedOnce(config, existingSchema);
      assertTrue(existingFlyway.info().applied().length >= 2,
          "schema existente deve registrar baseline e V1");

      String scopedUrl = withCurrentSchema(databaseUrl, existingSchema);
      PostgresDatabase legacyInitialization = new PostgresDatabase(scopedUrl);
      assertFalseBlank(legacyInitialization.testConnection());
      assertBranchCreatedOnce(config, existingSchema);
      assertEquals("preservar", scalar(config,
          "SELECT value FROM " + existingSchema + ".legacy_guard WHERE id = 1"));
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + emptySchema + " CASCADE");
        statement.execute("DROP SCHEMA IF EXISTS " + existingSchema + " CASCADE");
      }
    }
  }

  private String authorizedDatabaseUrl() {
    String databaseUrl = System.getenv("DATABASE_URL");
    boolean allowed = Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"));
    assumeFalse(!allowed || databaseUrl == null || databaseUrl.isBlank(),
        "Banco PostgreSQL descartável não autorizado; teste Flyway ignorado.");
    return databaseUrl;
  }

  private Flyway flyway(DatabaseUrlParser.JdbcConfig config, String schema) {
    return Flyway.configure()
        .dataSource(config.url(), config.username(), config.password())
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .validateOnMigrate(true)
        .load();
  }

  private void assertBranchCreatedOnce(DatabaseUrlParser.JdbcConfig config, String schema)
      throws Exception {
    assertEquals("1", scalar(config,
        "SELECT COUNT(*)::text FROM " + schema + ".filiais WHERE codigo = '281'"));
  }

  private String scalar(DatabaseUrlParser.JdbcConfig config, String sql) throws Exception {
    try (Connection connection = connect(config);
         Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }

  private Connection connect(DatabaseUrlParser.JdbcConfig config) throws Exception {
    return config.username().isBlank()
        ? DriverManager.getConnection(config.url())
        : DriverManager.getConnection(config.url(), config.username(), config.password());
  }

  private String schemaName(String prefix) {
    return "mncheck_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  private String withCurrentSchema(String databaseUrl, String schema) {
    return databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private void assertFalseBlank(String value) {
    assertTrue(value != null && !value.isBlank());
  }
}
