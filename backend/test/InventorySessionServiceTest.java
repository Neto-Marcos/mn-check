package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class InventorySessionServiceTest {
  @Test
  void createsFrozenBranchIsolatedSessionsWithControlledLifecycle() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_inventory_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }

    try {
      Flyway.configure()
          .dataSource(config.url(), config.username(), config.password())
          .schemas(schema)
          .defaultSchema(schema)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .validateOnMigrate(true)
          .load()
          .migrate();
      String scopedUrl = withCurrentSchema(databaseUrl, schema);
      Seed seed = seed(scopedUrl);
      InventorySessionService service = new InventorySessionService(scopedUrl);

      InventorySessionService.InventoryDetail general = service.create(
          new InventorySessionService.CreateCommand(seed.import281(), "281", "Inventário geral 281",
              "GERAL", "NORMAL", List.of()), "Operador 281");
      assertEquals("RASCUNHO", general.inventory().status());
      assertEquals(0, general.inventory().version());
      assertEquals(2, general.inventory().totalSkus());
      assertEquals(17, general.inventory().totalUnidades());
      assertEquals(15, balanceOf(general, "SKU-A"));

      mutateCurrentBalance(scopedUrl, seed.import281());
      assertEquals(15, balanceOf(service.loadDetail(general.inventory().id(), "281"), "SKU-A"),
          "snapshot não pode acompanhar alterações posteriores no saldo atual");

      InventorySessionService.BranchMismatchException mismatch = assertThrows(
          InventorySessionService.BranchMismatchException.class,
          () -> service.create(new InventorySessionService.CreateCommand(seed.import282(), "281",
              "Cruzado", "GERAL", "NORMAL", List.of()), "Operador"));
      assertEquals(422, mismatch.status());

      assertEquals(404, assertThrows(InventorySessionService.NotFoundException.class,
          () -> service.list("999")).status());
      assertEquals(422, assertThrows(InventorySessionService.InactiveBranchException.class,
          () -> service.list("283")).status());

      int beforeRollback = service.list("281").size();
      assertThrows(InventorySessionService.ValidationException.class,
          () -> service.create(new InventorySessionService.CreateCommand(seed.import281(), "281",
              "Parcial inválido", "PARCIAL", "NORMAL", List.of("SKU-A", "INEXISTENTE")), "Operador"));
      assertEquals(beforeRollback, service.list("281").size(),
          "falha ao copiar itens deve reverter a sessão inteira");

      InventorySessionService.InventoryDetail partial = service.create(
          new InventorySessionService.CreateCommand(seed.import281(), "281", "Parcial",
              "PARCIAL", "CEGO", List.of("SKU-B")), "Operador");
      assertEquals(1, partial.inventory().totalSkus());
      assertEquals("SKU-B", partial.items().get(0).sku());

      assertUniqueInventorySku(scopedUrl, general.inventory().id());

      InventorySessionService.InventoryDetail opened = service.open(
          general.inventory().id(), "281", 0, "Supervisor");
      assertEquals("ABERTO", opened.inventory().status());
      assertEquals(1, opened.inventory().version());
      assertEquals("Supervisor", opened.inventory().abertoPor());

      assertEquals(409, assertThrows(InventorySessionService.VersionConflictException.class,
          () -> service.start(general.inventory().id(), "281", 0, "Supervisor")).status());

      InventorySessionService.InventoryDetail started = service.start(
          general.inventory().id(), "281", 1, "Supervisor");
      assertEquals("EM_CONTAGEM", started.inventory().status());
      assertEquals(2, started.inventory().version());
      assertEquals(409, assertThrows(InventorySessionService.InvalidTransitionException.class,
          () -> service.cancel(general.inventory().id(), "281", 2, "Supervisor")).status());

      InventorySessionService.InventoryDetail cancelled = service.cancel(
          partial.inventory().id(), "281", 0, "Supervisor");
      assertEquals("CANCELADO", cancelled.inventory().status());
      assertEquals(1, cancelled.items().size(), "cancelamento deve preservar o snapshot");

      InventorySessionService.InventoryDetail branch282 = service.create(
          new InventorySessionService.CreateCommand(seed.import282(), "282", "Inventário 282",
              "GERAL", "NORMAL", List.of()), "Operador 282");
      assertEquals(1, service.list("282").size());
      assertTrue(service.list("281").stream().noneMatch(item -> item.id() == branch282.inventory().id()),
          "listagem da 281 não pode retornar sessão da 282");
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  private Seed seed(String databaseUrl) throws Exception {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    try (Connection connection = connect(config)) {
      long branch281 = scalarId(connection, "SELECT id FROM filiais WHERE codigo = '281'");
      long branch282;
      long branch283;
      try (PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO filiais (codigo, nome, ativa) VALUES
            ('282', 'Filial 282', TRUE), ('283', 'Filial 283', FALSE)
          RETURNING id, codigo
          """)) {
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          long firstId = result.getLong("id");
          String firstCode = result.getString("codigo");
          result.next();
          long secondId = result.getLong("id");
          branch282 = "282".equals(firstCode) ? firstId : secondId;
          branch283 = "283".equals(firstCode) ? firstId : secondId;
        }
      }
      assertTrue(branch283 > 0);
      long import281 = insertImport(connection, branch281, "saldo-281.pdf");
      long import282 = insertImport(connection, branch282, "saldo-282.pdf");
      insertBalance(connection, branch281, import281, "SKU-A", "Produto A 281", 15);
      insertBalance(connection, branch281, import281, "SKU-B", "Produto B 281", 2);
      insertBalance(connection, branch282, import282, "SKU-A", "Produto A 282", 37);
      return new Seed(import281, import282);
    }
  }

  private long insertImport(Connection connection, long branchId, String fileName) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO importacoes_saldo (nome_arquivo, quantidade_skus, filial_id)
        VALUES (?, 0, ?) RETURNING id
        """)) {
      statement.setString(1, fileName);
      statement.setLong(2, branchId);
      try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
    }
  }

  private void insertBalance(Connection connection, long branchId, long importId, String sku,
      String description, int balance) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO saldos (sku, saldo, importacao_id) VALUES (?, ?, ?)")) {
      statement.setString(1, sku); statement.setInt(2, balance); statement.setLong(3, importId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO estoque_produtos
          (filial_id, sku, descricao, saldo_sistema, importacao_id)
        VALUES (?, ?, ?, ?, ?)
        """)) {
      statement.setLong(1, branchId); statement.setString(2, sku);
      statement.setString(3, description); statement.setInt(4, balance); statement.setLong(5, importId);
      statement.executeUpdate();
    }
  }

  private void mutateCurrentBalance(String databaseUrl, long importId) throws Exception {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE saldos SET saldo = 17 WHERE importacao_id = " + importId
          + " AND sku = 'SKU-A'");
      statement.executeUpdate("UPDATE estoque_produtos SET saldo_sistema = 17 WHERE sku = 'SKU-A'"
          + " AND filial_id = (SELECT id FROM filiais WHERE codigo = '281')");
    }
  }

  private void assertUniqueInventorySku(String databaseUrl, long inventoryId) throws Exception {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    try (Connection connection = connect(config); PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO inventario_itens
          (inventario_id, sku, descricao_snapshot, saldo_snapshot)
        VALUES (?, 'SKU-A', 'Duplicado', 1)
        """)) {
      statement.setLong(1, inventoryId);
      assertThrows(SQLException.class, statement::executeUpdate);
    }
  }

  private int balanceOf(InventorySessionService.InventoryDetail detail, String sku) {
    return detail.items().stream().filter(item -> item.sku().equals(sku)).findFirst().orElseThrow()
        .saldoSnapshot();
  }

  private long scalarId(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
      result.next(); return result.getLong(1);
    }
  }

  private String authorizedDatabaseUrl() {
    String databaseUrl = System.getenv("DATABASE_URL");
    boolean allowed = Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"));
    assumeFalse(!allowed || databaseUrl == null || databaseUrl.isBlank(),
        "Banco PostgreSQL descartável não autorizado; teste de inventário ignorado.");
    return databaseUrl;
  }

  private Connection connect(DatabaseUrlParser.JdbcConfig config) throws SQLException {
    return config.username().isBlank() ? DriverManager.getConnection(config.url())
        : DriverManager.getConnection(config.url(), config.username(), config.password());
  }

  private String withCurrentSchema(String databaseUrl, String schema) {
    return databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private record Seed(long import281, long import282) {}
}
