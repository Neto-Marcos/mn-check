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

class InventoryInvestigationIntegrationTest {

  @Test
  void testInvestigationCompleteLifecycleAndMultiFilialIsolation() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_inv_" + UUID.randomUUID().toString().replace("-", "");

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
      InventoryInvestigationService investigationService = new InventoryInvestigationService(scopedUrl);

      // 1. Criar e iniciar inventário na Filial 281
      InventorySessionService.InventoryDetail created281 = sessionService.create(
          new InventorySessionService.CreateCommand(
              seed.import281(), "281", "Inventário Investigação 281", "GERAL", "NORMAL", List.of()
          ), "Supervisor 281"
      );
      long inv281Id = created281.inventory().id();
      sessionService.open(inv281Id, "281", 0, "Supervisor 281");
      sessionService.start(inv281Id, "281", 1, "Supervisor 281");

      InventoryCountingService.RoundDetail r1 = countingService.getActiveRound(inv281Id, "281");
      long r1Id = r1.id();

      // Contar SKU-A: Saldo 10, Contamos 10 -> CONFORME
      countingService.recordOccurrence(
          inv281Id, r1Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-A", 10, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // Contar SKU-B: Saldo 20, Contamos 18 -> DIVERGENTE (falta -2)
      countingService.recordOccurrence(
          inv281Id, r1Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 18, "DEPOSITO", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // SKU-C: Saldo 5, Não contado -> NAO_CONTADO (fechamento forçado)
      InventoryCountingService.RoundAuditResult audit = countingService.closeRound(
          inv281Id, r1Id, "281", true, "Supervisor 281"
      );

      long apuracaoConformeId = findApuracaoId(scopedUrl, r1Id, "SKU-A");
      long apuracaoDivergenteId = findApuracaoId(scopedUrl, r1Id, "SKU-B");
      long apuracaoNaoContadoId = findApuracaoId(scopedUrl, r1Id, "SKU-C");

      long itemBId = findItemId(scopedUrl, inv281Id, "SKU-B");
      long itemCId = findItemId(scopedUrl, inv281Id, "SKU-C");

      // 2. Criar inventário na Filial 282 para testes de isolamento multi-filial
      InventorySessionService.InventoryDetail created282 = sessionService.create(
          new InventorySessionService.CreateCommand(
              seed.import282(), "282", "Inventário Isolado 282", "GERAL", "NORMAL", List.of()
          ), "Supervisor 282"
      );
      long inv282Id = created282.inventory().id();
      long item282AId = findItemId(scopedUrl, inv282Id, "SKU-A");

      // CENÁRIO 1: Rejeitar investigação para item CONFORME (HTTP 400)
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.create(
              inv281Id, "281",
              new InventoryInvestigationService.CreateCommand(apuracaoConformeId, null, "Item conforme"),
              "Supervisor 281"
          )
      );

      // CENÁRIO 2: Criar investigação para item DIVERGENTE (Status inicial: PENDENTE)
      InventoryInvestigationService.InvestigationDetail createdInv = investigationService.create(
          inv281Id, "281",
          new InventoryInvestigationService.CreateCommand(apuracaoDivergenteId, "INVERSAO_PRODUTO", "Suspeita inicial de inversão"),
          "Supervisor 281"
      );
      assertNotNull(createdInv);
      UUID invId = createdInv.id();
      assertEquals("PENDENTE", createdInv.status());
      assertEquals("SKU-B", createdInv.sku());
      assertEquals("INVERSAO_PRODUTO", createdInv.causaSuspeita());
      assertNull(createdInv.causaConfirmada());
      assertEquals(0L, createdInv.version());

      // CENÁRIO 3: Rejeitar investigação duplicada para a mesma apuração (HTTP 409 / Unicidade)
      assertThrows(InventoryInvestigationService.ConflictException.class, () ->
          investigationService.create(
              inv281Id, "281",
              new InventoryInvestigationService.CreateCommand(apuracaoDivergenteId, null, "Duplicada"),
              "Supervisor 281"
          )
      );

      // CENÁRIO 4: Iniciar investigação -> PENDENTE transita para EM_INVESTIGACAO
      InventoryInvestigationService.InvestigationDetail started = investigationService.start(
          inv281Id, invId, "281",
          new InventoryInvestigationService.StartCommand("sup281", "Supervisor Carlos", "INVERSAO_PRODUTO", "Iniciando apuração no depósito"),
          "Supervisor 281"
      );
      assertEquals("EM_INVESTIGACAO", started.status());
      assertEquals("Supervisor Carlos", started.responsavelNome());

      // CENÁRIO 5: Atualizar causa suspeita sem alterar causa confirmada
      InventoryInvestigationService.InvestigationDetail updatedCause = investigationService.updateSuspectCause(
          inv281Id, invId, "281",
          new InventoryInvestigationService.SuspectCauseCommand("INVERSAO_VOLTAGEM", "Nova suspeita de voltagem trocada"),
          "Supervisor 281"
      );
      assertEquals("INVERSAO_VOLTAGEM", updatedCause.causaSuspeita());
      assertNull(updatedCause.causaConfirmada());

      // CENÁRIO 6: Transitar status para AGUARDANDO_EVIDENCIA
      InventoryInvestigationService.InvestigationDetail waiting = investigationService.changeStatus(
          inv281Id, invId, "281", "AGUARDANDO_EVIDENCIA", "Aguardando envio da NF pelo financeiro", "Supervisor 281"
      );
      assertEquals("AGUARDANDO_EVIDENCIA", waiting.status());

      // CENÁRIO 7: Adicionar evidência -> grava e transita automaticamente de volta para EM_INVESTIGACAO
      InventoryInvestigationService.EvidenceRecord evidence = investigationService.addEvidence(
          inv281Id, invId, "281",
          new InventoryInvestigationService.AddEvidenceCommand("NOTA_FISCAL", "NF 48921 recebida com 18 un faturadas", "NF-48921"),
          "Operador Fiscal"
      );
      assertNotNull(evidence);
      assertEquals("NOTA_FISCAL", evidence.tipo());
      assertEquals("Operador Fiscal", evidence.criadoPor());

      // Verificar detalhe atualizado
      InventoryInvestigationService.InvestigationDetail reloaded = investigationService.getDetail(inv281Id, invId, "281");
      assertEquals("EM_INVESTIGACAO", reloaded.status(), "Adicionar evidência quando AGUARDANDO_EVIDENCIA deve retornar para EM_INVESTIGACAO");
      assertEquals(1, reloaded.evidencias().size());

      // CENÁRIO 8: Vincular produto relacionado (SKU-C) com POSSIVEL_INVERSAO
      InventoryInvestigationService.LinkRecord link = investigationService.addLink(
          inv281Id, invId, "281",
          new InventoryInvestigationService.AddLinkCommand(itemCId, null, "POSSIVEL_INVERSAO", "Inversão entre SKU-B e SKU-C"),
          "Supervisor 281"
      );
      assertNotNull(link);
      assertEquals("SKU-C", link.relacionadoSku());
      assertEquals("POSSIVEL_INVERSAO", link.tipoVinculo());

      // CENÁRIO 9: Rejeitar self-linking (vincular item a si mesmo)
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.addLink(
              inv281Id, invId, "281",
              new InventoryInvestigationService.AddLinkCommand(itemBId, null, "MESMO_PRODUTO", "Self link"),
              "Supervisor 281"
          )
      );

      // CENÁRIO 10: Rejeitar vínculo com item pertencente a outra filial / outro inventário
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.addLink(
              inv281Id, invId, "281",
              new InventoryInvestigationService.AddLinkCommand(item282AId, null, "POSSIVEL_INVERSAO", "Cross filial link"),
              "Supervisor 281"
          )
      );

      // CENÁRIO 11: Isolamento Multi-Filial estrito (Filial 282 não acessa investigação da Filial 281)
      assertThrows(InventoryInvestigationService.NotFoundException.class, () ->
          investigationService.getDetail(inv281Id, invId, "282")
      );
      assertThrows(InventoryInvestigationService.NotFoundException.class, () ->
          investigationService.list(inv281Id, "282", null)
      );
      assertThrows(InventoryInvestigationService.NotFoundException.class, () ->
          investigationService.addEvidence(
              inv281Id, invId, "282",
              new InventoryInvestigationService.AddEvidenceCommand("OBSERVACAO", "Tentativa invasiva", null),
              "Hacker 282"
          )
      );

      // CENÁRIO 12: Validação ao resolver: não pode resolver com NAO_IDENTIFICADA e requer justificativa
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.resolve(
              inv281Id, invId, "281",
              new InventoryInvestigationService.ResolveCommand("NAO_IDENTIFICADA", "Tentando resolver sem causa"),
              "Supervisor 281"
          )
      );
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.resolve(
              inv281Id, invId, "281",
              new InventoryInvestigationService.ResolveCommand("INVERSAO_PRODUTO", "   "),
              "Supervisor 281"
          )
      );

      // CENÁRIO 13: Resolver divergência com causa confirmada
      InventoryInvestigationService.InvestigationDetail resolved = investigationService.resolve(
          inv281Id, invId, "281",
          new InventoryInvestigationService.ResolveCommand("INVERSAO_PRODUTO", "Confirmada inversão física com SKU-C no lote de entrada."),
          "Auditor Líder"
      );
      assertEquals("RESOLVIDA", resolved.status());
      assertEquals("INVERSAO_PRODUTO", resolved.causaConfirmada());
      assertEquals("Auditor Líder", resolved.resolvidoPor());
      assertNotNull(resolved.resolvidoEm());

      // CENÁRIO 14: Imutabilidade após resolução: bloqueia novas evidências e vínculos (HTTP 409)
      assertThrows(InventoryInvestigationService.ConflictException.class, () ->
          investigationService.addEvidence(
              inv281Id, invId, "281",
              new InventoryInvestigationService.AddEvidenceCommand("FOTO", "Foto tardia", null),
              "Operador"
          )
      );
      assertThrows(InventoryInvestigationService.ConflictException.class, () ->
          investigationService.addLink(
              inv281Id, invId, "281",
              new InventoryInvestigationService.AddLinkCommand(itemCId, null, "OUTRO", "Vínculo tardio"),
              "Operador"
          )
      );

      // CENÁRIO 15: Reabertura explícita com justificativa
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.reopen(inv281Id, invId, "281", new InventoryInvestigationService.ReopenCommand("curta"), "Gerente")
      );
      InventoryInvestigationService.InvestigationDetail reopened = investigationService.reopen(
          inv281Id, invId, "281",
          new InventoryInvestigationService.ReopenCommand("Reabrindo para incluir laudo pericial da assistência"),
          "Gerente Operacional"
      );
      assertEquals("EM_INVESTIGACAO", reopened.status());
      assertNull(reopened.resolvidoEm());
      assertNull(reopened.resolvidoPor());

      // CENÁRIO 16: Encerrar como SEM_CAUSA_IDENTIFICADA
      assertThrows(InventoryInvestigationService.ValidationException.class, () ->
          investigationService.closeUnresolved(
              inv281Id, invId, "281",
              new InventoryInvestigationService.UnresolvedCommand("poucos"),
              "Supervisor 281"
          )
      );
      InventoryInvestigationService.InvestigationDetail unresolved = investigationService.closeUnresolved(
          inv281Id, invId, "281",
          new InventoryInvestigationService.UnresolvedCommand("Esgotadas todas as buscas físicas e fiscais sem sucesso."),
          "Supervisor 281"
      );
      assertEquals("SEM_CAUSA_IDENTIFICADA", unresolved.status());
      assertEquals("NAO_IDENTIFICADA", unresolved.causaConfirmada());
      assertNotNull(unresolved.resolvidoEm());

      // CENÁRIO 17: Preservação total da contagem física e auditabilidade da Timeline
      try (Connection connection = connect(config); Statement stmt = connection.createStatement()) {
        // Garantir que as tabelas de contagem física continuam 100% íntegras
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + schema + ".ocorrencias_contagem")) {
          assertTrue(rs.next());
          assertEquals(2, rs.getInt(1), "As ocorrências originais de contagem não podem ser apagadas");
        }
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + schema + ".apuracoes_rodada WHERE estado = 'DIVERGENTE'")) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1), "A apuração da rodada permanece intacta");
        }
        // Verificar que eventos da timeline foram registrados sequencialmente
        try (ResultSet rs = stmt.executeQuery("SELECT tipo_evento FROM " + schema + ".eventos_investigacao WHERE investigacao_id = '" + invId + "' ORDER BY id ASC")) {
          List<String> expectedEvents = List.of(
              "CRIADA", "INICIADA", "CAUSA_SUSPEITA_ALTERADA", "STATUS_ALTERADO",
              "EVIDENCIA_ADICIONADA", "PRODUTO_RELACIONADO", "RESOLVIDA", "REABERTA", "ENCERRADA_SEM_CAUSA"
          );
          for (String expected : expectedEvents) {
            assertTrue(rs.next(), "Evento esperado não encontrado: " + expected);
            assertEquals(expected, rs.getString("tipo_evento"));
          }
        }
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
        "Banco PostgreSQL descartável não autorizado; teste de investigação ignorado.");
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

  private long findApuracaoId(String databaseUrl, long roundId, String sku) throws SQLException {
    DatabaseUrlParser.JdbcConfig cfg = DatabaseUrlParser.parse(databaseUrl);
    try (Connection conn = DriverManager.getConnection(cfg.url(), cfg.username(), cfg.password());
         PreparedStatement ps = conn.prepareStatement("SELECT id FROM apuracoes_rodada WHERE rodada_id = ? AND sku = ?")) {
      ps.setLong(1, roundId);
      ps.setString(2, sku);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
        throw new RuntimeException("Apuração não encontrada para sku: " + sku);
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
