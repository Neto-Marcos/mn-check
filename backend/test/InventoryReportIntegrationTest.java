package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class InventoryReportIntegrationTest {
  @Test
  void projectsRoundsFinalSnapshotBlindModeAndBranchIsolation() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_report_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    try {
      Flyway.configure().dataSource(config.url(), config.username(), config.password()).schemas(schema)
          .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true)
          .baselineVersion("0").validateOnMigrate(true).load().migrate();
      String scopedUrl = withCurrentSchema(databaseUrl, schema);
      Seed seed = seed(scopedUrl);
      InventorySessionService sessions = new InventorySessionService(scopedUrl);
      InventoryCountingService counting = new InventoryCountingService(scopedUrl);
      InventoryClosingService closing = new InventoryClosingService(scopedUrl);
      InventoryReportService reports = new InventoryReportService(scopedUrl, null);

      long normalId = sessions.create(new InventorySessionService.CreateCommand(seed.import281(), "281",
          "Relatório 281", "GERAL", "NORMAL", List.of()), "Gestor").inventory().id();
      sessions.open(normalId, "281", 0, "Gestor"); sessions.start(normalId, "281", 1, "Gestor");
      long roundId = counting.getActiveRound(normalId, "281").id();
      counting.recordOccurrence(normalId, roundId, "281", occurrence("SKU-A", 10, "DEPOSITO", "BOA", "MANUAL"), "Operador");
      counting.recordOccurrence(normalId, roundId, "281", occurrence("SKU-B", 0, "GERAL", "AVARIA", "SCANNER"), "Operador");

      var divergentDeposit = reports.project(normalId, "281", filter("R1", "DIVERGENTES", "DEPOSITO", "TODAS", ""));
      assertEquals(List.of("SKU-A"), divergentDeposit.itens().stream().map(InventoryReportService.ReportItem::sku).toList());
      assertEquals(-2, divergentDeposit.itens().getFirst().diferenca());
      assertThrows(InventoryReportService.ReportException.class, () -> reports.project(normalId, "282", filter("R1", "TODOS", "TODAS", "TODAS", "")));
      assertThrows(InventoryReportService.ReportException.class, () -> reports.project(999999, "281", filter("R1", "TODOS", "TODAS", "TODAS", "")));

      counting.closeRound(normalId, roundId, "281", false, "Gestor");
      closing.closeInventory(normalId, "281", new InventoryClosingService.CloseCommand("EXCEPCIONAL", "Validação automatizada do relatório histórico"),
          new LegacyAuthenticationClient.AuthenticatedUser("admin", "Administrador", "admin"));
      var finalReport = reports.project(normalId, "281", filter("RESULTADO_FINAL", "DIVERGENTES", "TODAS", "TODAS", "SKU-A"));
      assertEquals(1, finalReport.itens().size());
      assertEquals(-2, finalReport.itens().getFirst().diferenca());
      assertEquals(finalReport.itens(), reports.project(normalId, "281",
          filter("RESULTADO_FINAL", "DIVERGENTES", "TODAS", "TODAS", "SKU-A")).itens(),
          "o relatório encerrado deve ser reproduzido pelo snapshot persistido");

      long blindId = sessions.create(new InventorySessionService.CreateCommand(seed.import281(), "281",
          "Relatório Cego", "PARCIAL", "CEGO", List.of("SKU-A")), "Gestor").inventory().id();
      sessions.open(blindId, "281", 0, "Gestor"); sessions.start(blindId, "281", 1, "Gestor");
      long blindRound = counting.getActiveRound(blindId, "281").id();
      counting.recordOccurrence(blindId, blindRound, "281", occurrence("SKU-A", 10, "GERAL", "BOA", "MANUAL"), "Operador");
      var blindReport = reports.project(blindId, "281", filter("R1", "TODOS", "TODAS", "TODAS", ""));
      assertTrue(blindReport.camposProtegidos());
      assertNull(blindReport.itens().getFirst().saldo());
      assertNull(blindReport.itens().getFirst().diferenca());
      assertThrows(InventoryReportService.ReportException.class,
          () -> reports.project(blindId, "281", filter("R1", "DIVERGENTES", "TODAS", "TODAS", "")));
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  private InventoryCountingService.RecordOccurrenceCommand occurrence(String sku, int quantity,
      String location, String condition, String origin) {
    return new InventoryCountingService.RecordOccurrenceCommand(sku, quantity, location, condition,
        "DEFINIR", UUID.randomUUID(), origin, "Teste", Instant.now(), null);
  }

  private InventoryReportService.ReportFilter filter(String context, String status, String location,
      String condition, String search) {
    return new InventoryReportService.ReportFilter(context, status, location, condition, "TODAS", search, "SKU");
  }

  private Seed seed(String url) throws Exception {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(url);
    try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schemaFrom(url) + ", public");
      long branch281 = id(connection, "SELECT id FROM filiais WHERE codigo='281'");
      long branch282;
      try (ResultSet rs = statement.executeQuery("INSERT INTO filiais(codigo,nome,ativa) VALUES('282','Filial 282',TRUE) RETURNING id")) { rs.next(); branch282=rs.getLong(1); }
      long import281 = insertImport(connection, branch281, "281.pdf");
      long import282 = insertImport(connection, branch282, "282.pdf");
      insertStock(connection, branch281, import281, "SKU-A", "Produto A", 12);
      insertStock(connection, branch281, import281, "SKU-B", "Produto B", 0);
      insertStock(connection, branch282, import282, "SKU-A", "Produto A 282", 99);
      return new Seed(import281);
    }
  }

  private long insertImport(Connection c,long branch,String name)throws SQLException{try(PreparedStatement ps=c.prepareStatement("INSERT INTO importacoes_saldo(nome_arquivo,quantidade_skus,filial_id) VALUES(?,0,?) RETURNING id")){ps.setString(1,name);ps.setLong(2,branch);try(ResultSet rs=ps.executeQuery()){rs.next();return rs.getLong(1);}}}
  private void insertStock(Connection c,long branch,long imp,String sku,String description,int balance)throws SQLException{
    try(PreparedStatement ps=c.prepareStatement("INSERT INTO saldos(sku,saldo,importacao_id) VALUES(?,?,?)")){ps.setString(1,sku);ps.setInt(2,balance);ps.setLong(3,imp);ps.executeUpdate();}
    try(PreparedStatement ps=c.prepareStatement("INSERT INTO estoque_produtos(filial_id,sku,descricao,saldo_sistema,importacao_id) VALUES(?,?,?,?,?)")){ps.setLong(1,branch);ps.setString(2,sku);ps.setString(3,description);ps.setInt(4,balance);ps.setLong(5,imp);ps.executeUpdate();}
  }
  private long id(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement();ResultSet rs=s.executeQuery(sql)){rs.next();return rs.getLong(1);}}
  private String authorizedDatabaseUrl(){String url=System.getenv("DATABASE_URL");boolean allowed=Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"));assumeFalse(!allowed||url==null||url.isBlank(),"Banco PostgreSQL descartável não autorizado; teste de relatório ignorado.");return url;}
  private Connection connect(DatabaseUrlParser.JdbcConfig c)throws SQLException{return c.username().isBlank()?DriverManager.getConnection(c.url()):DriverManager.getConnection(c.url(),c.username(),c.password());}
  private String withCurrentSchema(String url,String schema){return url+(url.contains("?")?"&":"?")+"currentSchema="+schema+"&options=-c%20search_path%3D"+schema+",public";}
  private String schemaFrom(String url){String value=url.substring(url.indexOf("currentSchema=")+14);int amp=value.indexOf('&');return amp<0?value:value.substring(0,amp);}
  private record Seed(long import281){}
}
