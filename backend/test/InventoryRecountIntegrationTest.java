package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

class InventoryRecountIntegrationTest {

  @Test
  void testCompleteLifecycleFromR1ToR2AndMultiFilialIsolation() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_recount_" + UUID.randomUUID().toString().replace("-", "");

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
      Seed seed = seedData(scopedUrl);

      InventorySessionService sessionService = new InventorySessionService(scopedUrl);
      InventoryCountingService countingService = new InventoryCountingService(scopedUrl);

      // 1. Criar sessão de inventário na filial 281
      InventorySessionService.InventoryDetail created = sessionService.create(
          new InventorySessionService.CreateCommand(
              seed.import281(), "281", "Inventário Dia 5 - Filial 281", "GERAL", "NORMAL", List.of()
          ), "Supervisor 281"
      );
      long invId = created.inventory().id();

      // Abrir e Iniciar -> Rodada 1 criada atomicamente
      sessionService.open(invId, "281", 0, "Supervisor 281");
      sessionService.start(invId, "281", 1, "Supervisor 281");

      InventoryCountingService.RoundDetail r1 = countingService.getActiveRound(invId, "281");
      assertEquals(1, r1.numero());
      assertEquals("CONTAGEM", r1.tipo());
      assertEquals("EM_ANDAMENTO", r1.status());

      // 2. Contar SKU-A: Saldo é 10. Registramos 10 em VENDAS (BOA) -> CONFORME
      UUID event1 = UUID.randomUUID();
      countingService.recordOccurrence(
          invId, r1.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-A", 10, "VENDAS", "BOA", "DEFINIR", event1, "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // 3. Regra de GERAL: tentar registrar SKU-A em GERAL após VENDAS deve ser rejeitado com HTTP 409
      assertThrows(InventoryCountingService.ConflictException.class, () ->
          countingService.recordOccurrence(
              invId, r1.id(), "281",
              new InventoryCountingService.RecordOccurrenceCommand(
                  "SKU-A", 2, "GERAL", "BOA", "DEFINIR", UUID.randomUUID(), "MANUAL", "PWA", Instant.now(), null
              ), "Operador 1"
          )
      );

      // 4. Contar SKU-B: Saldo é 20. Registramos 12 em DEPOSITO + 6 em VENDAS = 18 -> DIVERGENTE (falta de -2)
      countingService.recordOccurrence(
          invId, r1.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 12, "DEPOSITO", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );
      countingService.recordOccurrence(
          invId, r1.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 6, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // SKU-C tem saldo 5 e NÃO FOI CONTADO

      // 5. Tentar encerrar R1 normalmente com item não contado (SKU-C) -> Bloqueado com 409
      InventoryCountingService.UncountedItemsConflictException uncountedEx = assertThrows(
          InventoryCountingService.UncountedItemsConflictException.class,
          () -> countingService.closeRound(invId, r1.id(), "281", false, "Supervisor 281")
      );
      assertEquals(1, uncountedEx.pendentes(), "Deve reportar exatamente 1 item pendente não contado");

      // 6. Encerramento forçado de R1 -> Sucesso e persistência em apuracoes_rodada
      InventoryCountingService.RoundAuditResult auditR1 = countingService.closeRound(
          invId, r1.id(), "281", true, "Supervisor 281"
      );
      assertEquals("FINALIZADA", auditR1.rodadaStatus());
      assertTrue(auditR1.encerramentoForcado());
      assertEquals(1, auditR1.pendentesNoFechamento());

      // Verificar resumo de apuração da R1
      assertEquals(3, auditR1.resumo().totalItens());
      assertEquals(1, auditR1.resumo().conformes(), "SKU-A (10 = 10)");
      assertEquals(1, auditR1.resumo().divergentes(), "SKU-B (18 vs 20)");
      assertEquals(1, auditR1.resumo().naoContados(), "SKU-C (não contado)");

      // 7. Imutabilidade: tentar gravar ocorrência após fechamento da R1 deve falhar
      assertThrows(InventoryCountingService.ConflictException.class, () ->
          countingService.recordOccurrence(
              invId, r1.id(), "281",
              new InventoryCountingService.RecordOccurrenceCommand(
                  "SKU-A", 1, "VENDAS", "BOA", "SOMAR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
              ), "Operador 1"
          )
      );

      // Fechamento concorrente subsequente deve falhar com 409
      assertThrows(InventoryCountingService.ConflictException.class, () ->
          countingService.closeRound(invId, r1.id(), "281", true, "Outro Supervisor")
      );

      // 8. Criação da Rodada 2
      // Obter IDs dos itens
      long idSkuA = findItemId(scopedUrl, invId, "SKU-A");
      long idSkuB = findItemId(scopedUrl, invId, "SKU-B");
      long idSkuC = findItemId(scopedUrl, invId, "SKU-C");

      // Tentar incluir item CONFORME (SKU-A) na recontagem deve ser rejeitado com 400
      assertThrows(InventoryCountingService.ValidationException.class, () ->
          countingService.createRecountRound(invId, "281", List.of(idSkuA, idSkuB), "Supervisor 281")
      );

      // Criar Rodada 2 apenas com itens elegíveis: SKU-B (divergente) e SKU-C (não contado)
      InventoryCountingService.RoundDetail r2 = countingService.createRecountRound(
          invId, "281", List.of(idSkuB, idSkuC), "Supervisor 281"
      );
      assertEquals(2, r2.numero());
      assertEquals("RECONTAGEM", r2.tipo());
      assertEquals("EM_ANDAMENTO", r2.status());
      assertEquals(2, r2.progresso().totalSkus(), "Escopo da R2 deve ter exatamente 2 itens");

      // Tentar criar R2 duplicada deve falhar
      assertThrows(InventoryCountingService.ConflictException.class, () ->
          countingService.createRecountRound(invId, "281", List.of(idSkuB), "Supervisor 281")
      );

      // 9. Cegueira Estrutural na Rodada 2:
      // listItems deve ocultar saldoSnapshot mesmo a sessão original sendo NORMAL
      InventoryCountingService.ItemListResult r2Items = countingService.listItems(invId, "281", null, null);
      assertEquals(2, r2Items.itens().size(), "Apenas itens de R2 devem ser retornados");
      for (InventoryCountingService.CountingItem item : r2Items.itens()) {
        assertNull(item.saldoSnapshot(), "DTO operacional da R2 NUNCA pode expor saldoSnapshot!");
      }

      // Tentar contar SKU-A em R2 deve ser rejeitado porque não está no escopo de R2
      assertThrows(InventoryCountingService.ConflictException.class, () ->
          countingService.recordOccurrence(
              invId, r2.id(), "281",
              new InventoryCountingService.RecordOccurrenceCommand(
                  "SKU-A", 10, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
              ), "Operador 2"
          )
      );

      // 10. Recontar em R2:
      // SKU-B: Recontamos 20 (15 em deposito + 5 em vendas). Como saldo é 20 -> CONFORME_APOS_RECONTAGEM
      var r2Occ1 = countingService.recordOccurrence(
          invId, r2.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 15, "DEPOSITO", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 2"
      );
      assertNull(r2Occ1.saldoSnapshot(), "Retorno de ocorrência na R2 deve manter saldoSnapshot oculto");

      countingService.recordOccurrence(
          invId, r2.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 5, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 2"
      );

      // SKU-C: Recontamos 4 em GERAL. Como saldo é 5 -> DIVERGENCIA_CONFIRMADA
      countingService.recordOccurrence(
          invId, r2.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-C", 4, "GERAL", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 2"
      );

      // 11. Encerrar Rodada 2
      InventoryCountingService.RoundAuditResult auditR2 = countingService.closeRound(
          invId, r2.id(), "281", false, "Supervisor 281"
      );
      assertEquals("FINALIZADA", auditR2.rodadaStatus());
      assertEquals(1, auditR2.resumo().conformesAposRecontagem(), "SKU-B atingiu conformidade na R2");
      assertEquals(1, auditR2.resumo().divergenciasConfirmadas(), "SKU-C confirmou divergência (4 vs 5)");

      // 12. Isolamento Multi-Filial Estrito:
      // Filial 282 não consegue acessar, contar ou auditar a sessão da 281
      assertThrows(InventoryCountingService.NotFoundException.class, () ->
          countingService.getActiveRound(invId, "282")
      );
      assertThrows(InventoryCountingService.NotFoundException.class, () ->
          countingService.getRoundAudit(invId, r1.id(), "282")
      );
      assertThrows(InventoryCountingService.NotFoundException.class, () ->
          countingService.getRoundAudit(invId, r2.id(), "282")
      );
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  @Test
  void testExplicitZeroOccurrenceProducesCountedDivergence() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_zero_" + UUID.randomUUID().toString().replace("-", "");

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
      Seed seed = seedData(scopedUrl);

      InventorySessionService sessionService = new InventorySessionService(scopedUrl);
      InventoryCountingService countingService = new InventoryCountingService(scopedUrl);

      // Criar inventário parcial contendo SKU-C cujo saldo é 5
      InventorySessionService.InventoryDetail created = sessionService.create(
          new InventorySessionService.CreateCommand(
              seed.import281(), "281", "Inventário Teste Zero Explícito", "PARCIAL", "NORMAL", List.of("SKU-C")
          ), "Supervisor 281"
      );
      long invId = created.inventory().id();

      // Abrir e iniciar
      sessionService.open(invId, "281", 0, "Supervisor 281");
      sessionService.start(invId, "281", 1, "Supervisor 281");

      InventoryCountingService.RoundDetail r1 = countingService.getActiveRound(invId, "281");
      long r1Id = r1.id();

      // Gravar ocorrência explícita DEFINIR 0
      countingService.recordOccurrence(
          invId, r1Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-C", 0, "GERAL", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // Como o item foi contado (quantidade 0), o encerramento normal NÃO deve ser bloqueado
      InventoryCountingService.RoundAuditResult audit = countingService.closeRound(
          invId, r1Id, "281", false, "Supervisor 281"
      );

      // Asserções obrigatórias
      assertFalse(audit.encerramentoForcado(), "Encerramento não pode ser forçado pois o item foi contado");
      assertEquals(0, audit.pendentesNoFechamento(), "Não deve haver itens pendentes no fechamento");
      assertEquals(1, audit.resumo().totalItens());
      assertEquals(1, audit.resumo().divergentes(), "Ocorrência zero para saldo 5 deve ser DIVERGENTE");
      assertEquals(0, audit.resumo().naoContados(), "NÃO pode resultar em NAO_CONTADO");

      InventoryCountingService.AuditItem itemAudit = audit.itens().get(0);
      assertEquals("SKU-C", itemAudit.sku());
      assertEquals(5, itemAudit.saldoSnapshot());
      assertTrue(itemAudit.contado(), "contado deve ser true");
      assertEquals(0, itemAudit.quantidadeFisica(), "quantidade_fisica deve ser 0");
      assertEquals(-5, itemAudit.diferenca(), "diferenca deve ser -5");
      assertEquals("DIVERGENTE", itemAudit.estado(), "estado deve ser DIVERGENTE");

      // Verificação direta no PostgreSQL na tabela apuracoes_rodada
      try (Connection connection = connect(config);
           Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT contado, quantidade_fisica, diferenca, estado FROM "
               + schema + ".apuracoes_rodada WHERE rodada_id = " + r1Id)) {
        assertTrue(rs.next(), "Registro deve existir em apuracoes_rodada");
        assertTrue(rs.getBoolean("contado"), "Coluna contado no banco deve ser true");
        assertEquals(0, rs.getInt("quantidade_fisica"), "Coluna quantidade_fisica deve ser 0");
        assertEquals(-5, rs.getInt("diferenca"), "Coluna diferenca deve ser -5");
        assertEquals("DIVERGENTE", rs.getString("estado"), "Coluna estado deve ser DIVERGENTE");
      }
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  private String authorizedDatabaseUrl() {
    String databaseUrl = System.getenv("DATABASE_URL");
    boolean allowed = Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"));
    assumeFalse(!allowed || databaseUrl == null || databaseUrl.isBlank(),
        "Banco PostgreSQL descartável não autorizado; teste de integração ignorado.");
    return databaseUrl;
  }

  private Connection connect(DatabaseUrlParser.JdbcConfig config) throws Exception {
    return config.username().isBlank()
        ? DriverManager.getConnection(config.url())
        : DriverManager.getConnection(config.url(), config.username(), config.password());
  }

  private String withCurrentSchema(String databaseUrl, String schema) {
    return databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private long findItemId(String databaseUrl, long inventoryId, String sku) throws SQLException {
    DatabaseUrlParser.JdbcConfig cfg = DatabaseUrlParser.parse(databaseUrl);
    try (Connection conn = DriverManager.getConnection(cfg.url(), cfg.username(), cfg.password());
         PreparedStatement ps = conn.prepareStatement("SELECT id FROM inventario_itens WHERE inventario_id = ? AND sku = ?")) {
      ps.setLong(1, inventoryId);
      ps.setString(2, sku);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
        throw new RuntimeException("Item não encontrado: " + sku);
      }
    }
  }

  private record Seed(long branch281, long branch282, long import281, long import282) {}

  private Seed seedData(String databaseUrl) throws Exception {
    DatabaseUrlParser.JdbcConfig cfg = DatabaseUrlParser.parse(databaseUrl);
    try (Connection conn = DriverManager.getConnection(cfg.url(), cfg.username(), cfg.password());
         Statement st = conn.createStatement()) {
      long b281 = scalarLong(st, "SELECT id FROM filiais WHERE codigo = '281'");
      st.execute("INSERT INTO filiais (codigo, nome, ativa) VALUES ('282', 'Filial 282', true) ON CONFLICT DO NOTHING");
      long b282 = scalarLong(st, "SELECT id FROM filiais WHERE codigo = '282'");

      long imp281 = scalarLong(st, "INSERT INTO importacoes_saldo (nome_arquivo, quantidade_skus, filial_id) VALUES ('saldo-281.pdf', 3, " + b281 + ") RETURNING id");
      long imp282 = scalarLong(st, "INSERT INTO importacoes_saldo (nome_arquivo, quantidade_skus, filial_id) VALUES ('saldo-282.pdf', 1, " + b282 + ") RETURNING id");

      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-A', 10)");
      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-B', 20)");
      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-C', 5)");

      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp282 + ", 'SKU-A', 50)");

      return new Seed(b281, b282, imp281, imp282);
    }
  }

  private long scalarLong(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      if (rs.next()) return rs.getLong(1);
      throw new SQLException("Sem retorno para: " + sql);
    }
  }
}
