package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class InventoryRc2EndToEndTest {

  @Test
  void completeFiveSkuScenarioFromCreationThroughImmutableFinalSnapshot() throws Exception {
    String databaseUrl = System.getenv("DATABASE_URL");
    assumeTrue(Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"))
        && databaseUrl != null && !databaseUrl.isBlank(), "PostgreSQL descartável não autorizado");
    DatabaseUrlParser.JdbcConfig base = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_rc2_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = connect(base); Statement s = c.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
    try {
      Flyway.configure().dataSource(base.url(), base.username(), base.password()).schemas(schema)
          .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true)
          .baselineVersion("0").validateOnMigrate(true).load().migrate();
      String url = databaseUrl + (databaseUrl.contains("?") ? "&" : "?")
          + "currentSchema=" + schema + "&options=-c%20search_path=" + schema + ",public";
      long importId = seedFiveSkus(url);
      InventorySessionService sessions = new InventorySessionService(url);
      InventoryCountingService counting = new InventoryCountingService(url);
      InventoryInvestigationService investigations = new InventoryInvestigationService(url);
      InventoryClosingService closing = new InventoryClosingService(url);
      InventoryReportService reports = new InventoryReportService(url, null);
      var stock = new LegacyAuthenticationClient.AuthenticatedUser("stock", "Conferente", "stock");

      var created = sessions.create(new InventorySessionService.CreateCommand(importId, "281", "RC2 E2E",
          "GERAL", "NORMAL", List.of()), "Supervisor");
      long inventoryId = created.inventory().id();
      sessions.open(inventoryId, "281", 0, "Supervisor");
      sessions.start(inventoryId, "281", 1, "Supervisor");
      long r1 = counting.getActiveRound(inventoryId, "281").id();

      count(counting, inventoryId, r1, "SKU-A", 10);
      count(counting, inventoryId, r1, "SKU-B", 4);
      count(counting, inventoryId, r1, "SKU-C", 9);
      count(counting, inventoryId, r1, "SKU-D", 0);
      var r1Audit = counting.closeRound(inventoryId, r1, "281", true, "Supervisor");
      assertEquals(5, r1Audit.itens().size());
      assertEquals("CONFORME", state(r1Audit, "SKU-A"));
      assertEquals("DIVERGENTE", state(r1Audit, "SKU-B"));
      assertEquals("DIVERGENTE", state(r1Audit, "SKU-C"));
      assertEquals("CONFORME", state(r1Audit, "SKU-D"));
      assertEquals("NAO_CONTADO", state(r1Audit, "SKU-E"));

      long itemB = itemId(url, inventoryId, "SKU-B");
      long itemC = itemId(url, inventoryId, "SKU-C");
      long itemE = itemId(url, inventoryId, "SKU-E");
      long r2 = counting.createRecountRound(inventoryId, "281", List.of(itemB, itemC, itemE), "Supervisor").id();
      assertTrue(counting.listItems(inventoryId, "281", null, null).itens().stream()
          .allMatch(item -> item.saldoSnapshot() == null), "R2 permanece estruturalmente cega");
      count(counting, inventoryId, r2, "SKU-B", 5);
      count(counting, inventoryId, r2, "SKU-C", 9);
      count(counting, inventoryId, r2, "SKU-E", 3);
      var r2Audit = counting.closeRound(inventoryId, r2, "281", false, "Supervisor");
      assertEquals("CONFORME_APOS_RECONTAGEM", state(r2Audit, "SKU-B"));
      assertEquals("DIVERGENCIA_CONFIRMADA", state(r2Audit, "SKU-C"));
      assertEquals("CONFORME_APOS_RECONTAGEM", state(r2Audit, "SKU-E"));

      long auditC = auditId(url, r2, "SKU-C");
      UUID investigationId = investigations.create(inventoryId, "281",
          new InventoryInvestigationService.CreateCommand(auditC, "ERRO_CONTAGEM", "Suspeita para validação E2E"), "Supervisor").id();
      investigations.start(inventoryId, investigationId, "281",
          new InventoryInvestigationService.StartCommand("stock", "Conferente", "ERRO_CONTAGEM", "Análise iniciada"), "Supervisor");
      investigations.updateSuspectCause(inventoryId, investigationId, "281",
          new InventoryInvestigationService.SuspectCauseCommand("AVARIA", "Avaria identificada"), "Conferente");
      investigations.addEvidence(inventoryId, investigationId, "281",
          new InventoryInvestigationService.AddEvidenceCommand("OBSERVACAO", "Produto conferido fisicamente", "E2E-1"), "Conferente");
      investigations.addLink(inventoryId, investigationId, "281",
          new InventoryInvestigationService.AddLinkCommand(itemB, null, "POSSIVEL_INVERSAO", "Comparação com SKU-B"), "Conferente");
      investigations.resolve(inventoryId, investigationId, "281",
          new InventoryInvestigationService.ResolveCommand("AVARIA", "Divergência confirmada e documentada"), "Supervisor");

      assertTrue(closing.validateClosing(inventoryId, "281").podeFechar());
      var result = closing.closeInventory(inventoryId, "281",
          new InventoryClosingService.CloseCommand("NORMAL", null), stock);
      assertEquals("ENCERRADO", result.inventarioStatus());
      assertEquals(5, closing.getDetailedHistory(inventoryId, "281").itens().size());
      var report = reports.project(inventoryId, "281",
          new InventoryReportService.ReportFilter("RESULTADO_FINAL", "TODOS", "TODAS", "TODAS", "TODAS", "", "SKU"));
      assertEquals(5, report.itens().size());
      try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(reports.exportXlsx(report)))) {
        assertEquals(5, workbook.getSheetAt(0).getLastRowNum() - 8);
      }
      assertEquals(5, scalar(url, "SELECT COUNT(*) FROM resultado_inventario_itens WHERE inventario_id=" + inventoryId));

      assert409(() -> count(counting, inventoryId, r1, "SKU-A", 10));
      assert409(() -> counting.createRecountRound(inventoryId, "281", List.of(itemB), "Supervisor"));
      assert409(() -> counting.closeRound(inventoryId, r1, "281", false, "Supervisor"));
      assert409(() -> investigations.create(inventoryId, "281",
          new InventoryInvestigationService.CreateCommand(auditC, "AVARIA", "Pós fechamento"), "Supervisor"));
      assert409(() -> investigations.addEvidence(inventoryId, investigationId, "281",
          new InventoryInvestigationService.AddEvidenceCommand("OBSERVACAO", "Pós fechamento", null), "Supervisor"));
      assert409(() -> investigations.addLink(inventoryId, investigationId, "281",
          new InventoryInvestigationService.AddLinkCommand(itemE, null, "OUTRO", "Pós fechamento"), "Supervisor"));
      assert409(() -> investigations.reopen(inventoryId, investigationId, "281",
          new InventoryInvestigationService.ReopenCommand("Reabertura pós fechamento"), "Supervisor"));
    } finally {
      try (Connection c = connect(base); Statement s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
  }

  private void count(InventoryCountingService service,long inventory,long round,String sku,int quantity){service.recordOccurrence(inventory,round,"281",new InventoryCountingService.RecordOccurrenceCommand(sku,quantity,"GERAL","BOA","DEFINIR",UUID.randomUUID(),"MANUAL","E2E",Instant.now(),null),"Operador");}
  private String state(InventoryCountingService.RoundAuditResult result,String sku){return result.itens().stream().filter(i->sku.equals(i.sku())).findFirst().orElseThrow().estado();}
  private void assert409(ThrowingRunnable action){RuntimeException ex=assertThrows(RuntimeException.class,action::run);int status=ex instanceof InventoryCountingService.CountingException e?e.status():ex instanceof InventoryInvestigationService.InvestigationException e?e.status():-1;assertEquals(409,status);}
  private long seedFiveSkus(String url)throws Exception{DatabaseUrlParser.JdbcConfig cfg=DatabaseUrlParser.parse(url);try(Connection c=connect(cfg);Statement s=c.createStatement()){long branch=scalar(s,"SELECT id FROM filiais WHERE codigo='281'");long imp=scalar(s,"INSERT INTO importacoes_saldo(nome_arquivo,quantidade_skus,filial_id) VALUES ('rc2.pdf',5,"+branch+") RETURNING id");String[]skus={"A","B","C","D","E"};int[]balances={10,5,8,0,3};for(int i=0;i<5;i++)s.executeUpdate("INSERT INTO saldos(importacao_id,sku,saldo) VALUES ("+imp+",'SKU-"+skus[i]+"',"+balances[i]+")");return imp;}}
  private long itemId(String url,long inv,String sku)throws Exception{return queryId(url,"SELECT id FROM inventario_itens WHERE inventario_id="+inv+" AND sku='"+sku+"'");}
  private long auditId(String url,long round,String sku)throws Exception{return queryId(url,"SELECT id FROM apuracoes_rodada WHERE rodada_id="+round+" AND sku='"+sku+"'");}
  private long queryId(String url,String sql)throws Exception{try(Connection c=connect(DatabaseUrlParser.parse(url));Statement s=c.createStatement()){return scalar(s,sql);}}
  private int scalar(String url,String sql)throws Exception{return(int)queryId(url,sql);}
  private long scalar(Statement s,String sql)throws Exception{try(ResultSet rs=s.executeQuery(sql)){rs.next();return rs.getLong(1);}}
  private Connection connect(DatabaseUrlParser.JdbcConfig c)throws Exception{return c.username().isBlank()?DriverManager.getConnection(c.url()):DriverManager.getConnection(c.url(),c.username(),c.password());}
  @FunctionalInterface private interface ThrowingRunnable{void run()throws Exception;}
}
