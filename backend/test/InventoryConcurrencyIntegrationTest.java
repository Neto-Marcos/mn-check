package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class InventoryConcurrencyIntegrationTest {

  @Test
  void occurrenceAndRoundClosingAreSerializedWithoutOrphanedCounts() throws Exception {
    withDatabase(scopedUrl -> {
      InventoryCountingService counting = new InventoryCountingService(scopedUrl);
      for (int attempt = 0; attempt < 10; attempt++) {
        int iteration = attempt;
        Seed seed = seedOpenRound(scopedUrl, "RACE-OCC-" + iteration, 1);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
          Future<Boolean> occurrence = pool.submit(() -> {
            start.await();
            try {
              counting.recordOccurrence(seed.inventoryId(), seed.roundId(), "281",
                  new InventoryCountingService.RecordOccurrenceCommand("RACE-OCC-" + iteration, 1,
                      "GERAL", "BOA", "DEFINIR", UUID.randomUUID(), "MANUAL", "race", Instant.now(), null),
                  "Operador");
              return true;
            } catch (InventoryCountingService.ConflictException expected) {
              return false;
            }
          });
          Future<?> closing = pool.submit(() -> {
            start.await();
            counting.closeRound(seed.inventoryId(), seed.roundId(), "281", true, "Supervisor");
            return null;
          });
          boolean occurrenceCommitted = occurrence.get();
          closing.get();
          int persisted = scalar(scopedUrl,
              "SELECT COUNT(*) FROM ocorrencias_contagem WHERE rodada_id=" + seed.roundId());
          int audited = scalar(scopedUrl,
              "SELECT CASE WHEN contado THEN 1 ELSE 0 END FROM apuracoes_rodada WHERE rodada_id=" + seed.roundId());
          assertEquals(occurrenceCommitted ? 1 : 0, persisted);
          assertEquals(persisted, audited, "ocorrência persistida nunca pode ficar fora da apuração");
        }
      }
    });
  }

  @Test
  void investigationReopenAndInventoryClosingAreSerialized() throws Exception {
    withDatabase(scopedUrl -> {
      InvestigationSeed seed = seedResolvedInvestigation(scopedUrl);
      InventoryInvestigationService investigations = new InventoryInvestigationService(scopedUrl);
      InventoryClosingService closing = new InventoryClosingService(scopedUrl);
      LegacyAuthenticationClient.AuthenticatedUser stock =
          new LegacyAuthenticationClient.AuthenticatedUser("stock", "Conferente", "stock");
      CyclicBarrier start = new CyclicBarrier(2);
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        Future<Boolean> reopen = pool.submit(() -> {
          start.await();
          try {
            investigations.reopen(seed.inventoryId(), seed.investigationId(), "281",
                new InventoryInvestigationService.ReopenCommand("Reabertura concorrente controlada"), "Auditor");
            return true;
          } catch (InventoryInvestigationService.ConflictException expected) {
            return false;
          }
        });
        Future<Boolean> close = pool.submit(() -> {
          start.await();
          try {
            closing.closeInventory(seed.inventoryId(), "281",
                new InventoryClosingService.CloseCommand("NORMAL", null), stock);
            return true;
          } catch (InventoryClosingService.ConflictException expected) {
            return false;
          }
        });
        boolean reopened = reopen.get();
        boolean closed = close.get();
        assertTrue(reopened ^ closed, "somente a mutação ou o fechamento pode vencer a corrida");
        String inventoryStatus = text(scopedUrl, "SELECT status FROM inventarios WHERE id=" + seed.inventoryId());
        String investigationStatus = text(scopedUrl,
            "SELECT status FROM investigacoes_divergencia WHERE id='" + seed.investigationId() + "'");
        if (closed) {
          assertEquals("ENCERRADO", inventoryStatus);
          assertEquals("RESOLVIDA", investigationStatus);
        } else {
          assertEquals("EM_CONTAGEM", inventoryStatus);
          assertEquals("EM_INVESTIGACAO", investigationStatus);
        }
      }
    });
  }

  private void withDatabase(ThrowingConsumer<String> test) throws Exception {
    String url = System.getenv("DATABASE_URL");
    assumeTrue(Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS")) && url != null && !url.isBlank(),
        "PostgreSQL descartável não autorizado");
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(url);
    String schema = "mncheck_race_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = connect(config); Statement s = c.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
    try {
      Flyway.configure().dataSource(config.url(), config.username(), config.password()).schemas(schema)
          .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true)
          .baselineVersion("0").validateOnMigrate(true).load().migrate();
      test.accept(withCurrentSchema(url, schema));
    } finally {
      try (Connection c = connect(config); Statement s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
  }

  private Seed seedOpenRound(String url, String sku, int balance) throws Exception {
    try (Connection c = connect(DatabaseUrlParser.parse(url))) {
      c.setAutoCommit(false);
      long branch = id(c, "SELECT id FROM filiais WHERE codigo='281'");
      long imp;
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO importacoes_saldo(nome_arquivo,quantidade_skus,filial_id) VALUES ('race.pdf',1,?) RETURNING id")) {
        ps.setLong(1, branch); try (ResultSet rs = ps.executeQuery()) { rs.next(); imp = rs.getLong(1); }
      }
      long inventory = insertId(c, "INSERT INTO inventarios(filial_id,importacao_saldo_id,nome,tipo,modo,status,criado_por) VALUES ("+branch+","+imp+",'Race','GERAL','NORMAL','EM_CONTAGEM','test') RETURNING id");
      insertId(c, "INSERT INTO inventario_itens(inventario_id,sku,descricao_snapshot,saldo_snapshot) VALUES ("+inventory+",'"+sku+"','Race',"+balance+") RETURNING id");
      long round = insertId(c, "INSERT INTO rodadas_contagem(inventario_id,numero,tipo,status,iniciada_por) VALUES ("+inventory+",1,'CONTAGEM','EM_ANDAMENTO','test') RETURNING id");
      c.commit(); return new Seed(inventory, round);
    }
  }

  private InvestigationSeed seedResolvedInvestigation(String url) throws Exception {
    Seed base = seedOpenRound(url, "RACE-INV", 1);
    try (Connection c = connect(DatabaseUrlParser.parse(url))) {
      long branch = id(c, "SELECT id FROM filiais WHERE codigo='281'");
      long item = id(c, "SELECT id FROM inventario_itens WHERE inventario_id=" + base.inventoryId());
      try (Statement s = c.createStatement()) {
        s.executeUpdate("UPDATE rodadas_contagem SET status='FINALIZADA',finalizada_em=now(),finalizada_por='test' WHERE id="+base.roundId());
      }
      long audit = insertId(c, "INSERT INTO apuracoes_rodada(rodada_id,inventario_item_id,sku,saldo_snapshot,contado,quantidade_fisica,diferenca,estado,apurado_por) VALUES ("+base.roundId()+","+item+",'RACE-INV',1,true,2,1,'DIVERGENTE','test') RETURNING id");
      UUID investigation = UUID.randomUUID();
      try (PreparedStatement ps = c.prepareStatement("INSERT INTO investigacoes_divergencia(id,filial_id,inventario_id,inventario_item_id,apuracao_id,sku,status,causa_confirmada,conclusao,criado_por,atualizado_por,resolvido_em,resolvido_por) VALUES (?,?,?,?,?,'RACE-INV','RESOLVIDA','ERRO_CONTAGEM','Resolvida', 'test','test',now(),'test')")) {
        ps.setObject(1, investigation); ps.setLong(2, branch); ps.setLong(3, base.inventoryId()); ps.setLong(4, item); ps.setLong(5, audit); ps.executeUpdate();
      }
      return new InvestigationSeed(base.inventoryId(), investigation);
    }
  }

  private int scalar(String url, String sql) throws Exception { try (Connection c=connect(DatabaseUrlParser.parse(url)); Statement s=c.createStatement(); ResultSet rs=s.executeQuery(sql)){rs.next();return rs.getInt(1);} }
  private String text(String url, String sql) throws Exception { try (Connection c=connect(DatabaseUrlParser.parse(url)); Statement s=c.createStatement(); ResultSet rs=s.executeQuery(sql)){rs.next();return rs.getString(1);} }
  private long id(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();ResultSet rs=s.executeQuery(sql)){rs.next();return rs.getLong(1);}}
  private long insertId(Connection c,String sql)throws Exception{return id(c,sql);}
  private Connection connect(DatabaseUrlParser.JdbcConfig cfg)throws Exception{return cfg.username().isBlank()?DriverManager.getConnection(cfg.url()):DriverManager.getConnection(cfg.url(),cfg.username(),cfg.password());}
  private String withCurrentSchema(String url,String schema){String separator=url.contains("?")?"&":"?";return url+separator+"currentSchema="+schema;}
  private record Seed(long inventoryId,long roundId){}
  private record InvestigationSeed(long inventoryId,UUID investigationId){}
  @FunctionalInterface private interface ThrowingConsumer<T>{void accept(T value)throws Exception;}
}
