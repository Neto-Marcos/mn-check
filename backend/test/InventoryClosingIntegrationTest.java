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

class InventoryClosingIntegrationTest {

  @Test
  void testInventoryClosingLifecycleAndImmutability() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_cls_" + UUID.randomUUID().toString().replace("-", "");

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
      Seed seed = seedData(scopedUrl, schema);

      InventorySessionService sessionService = new InventorySessionService(scopedUrl);
      InventoryCountingService countingService = new InventoryCountingService(scopedUrl);
      InventoryInvestigationService investigationService = new InventoryInvestigationService(scopedUrl);
      InventoryClosingService closingService = new InventoryClosingService(scopedUrl);

      LegacyAuthenticationClient.AuthenticatedUser adminUser =
          new LegacyAuthenticationClient.AuthenticatedUser("admin-1", "Administrador Regional", "admin");
      LegacyAuthenticationClient.AuthenticatedUser stockUser =
          new LegacyAuthenticationClient.AuthenticatedUser("stock-1", "Conferente", "stock");

      // =========================================================================
      // CENÁRIO 1: INVENTÁRIO 1 - CICLO COM R2, DIVERGÊNCIA, INVESTIGAÇÃO E FECHAMENTO EXCEPCIONAL
      // =========================================================================
      InventorySessionService.InventoryDetail inv1 = sessionService.create(
          new InventorySessionService.CreateCommand(
              seed.import281(), "281", "Inventário Geral 281", "GERAL", "NORMAL", List.of()
          ), "Supervisor 281"
      );
      long inv1Id = inv1.inventory().id();
      sessionService.open(inv1Id, "281", 0, "Supervisor 281");
      sessionService.start(inv1Id, "281", 1, "Supervisor 281");

      InventoryCountingService.RoundDetail r1 = countingService.getActiveRound(inv1Id, "281");
      long r1Id = r1.id();

      // Contagem R1:
      // SKU-A: saldo 10, contamos 10 -> CONFORME
      countingService.recordOccurrence(
          inv1Id, r1Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-A", 10, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // SKU-B: saldo 20, contamos 18 -> DIVERGENTE (falta -2)
      countingService.recordOccurrence(
          inv1Id, r1Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 18, "DEPOSITO", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // SKU-C: saldo 5, contamos 8 -> DIVERGENTE (sobra +3)
      countingService.recordOccurrence(
          inv1Id, r1Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-C", 8, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 1"
      );

      // SKU-D: saldo 4, NÃO CONTADO

      // Fechar R1 forçado devido ao SKU-D não contado
      countingService.closeRound(inv1Id, r1Id, "281", true, "Supervisor 281");

      // 1. Pré-validação com divergências não tratadas
      InventoryClosingService.ValidationResult val1 = closingService.validateClosing(inv1Id, "281");
      assertFalse(val1.podeFechar(), "Não deve permitir fechar com divergências sem investigação e itens não contados");
      assertTrue(val1.pendencias().stream().anyMatch(p -> "DIVERGENCIA_SEM_INVESTIGACAO".equals(p.codigo())));
      assertTrue(val1.pendencias().stream().anyMatch(p -> "ITENS_NAO_CONTADOS".equals(p.codigo())));

      // Tentativa de fechamento normal deve falhar com 409
      InventoryClosingService.ConflictException exNormal1 = assertThrows(
          InventoryClosingService.ConflictException.class,
          () -> closingService.closeInventory(
              inv1Id, "281", new InventoryClosingService.CloseCommand("NORMAL", null), adminUser
          )
      );
      assertEquals(409, exNormal1.status());

      // 2. Iniciar Recontagem R2 para SKU-B e SKU-C
      long itemBId = findItemId(scopedUrl, inv1Id, "SKU-B");
      long itemCId = findItemId(scopedUrl, inv1Id, "SKU-C");

      InventoryCountingService.RoundDetail r2 = countingService.createRecountRound(
          inv1Id, "281", List.of(itemBId, itemCId), "Supervisor 281"
      );
      long r2Id = r2.id();

      // Pré-validação durante R2 ativa deve acusar RODADA_ATIVA
      InventoryClosingService.ValidationResult valR2Ativa = closingService.validateClosing(inv1Id, "281");
      assertFalse(valR2Ativa.podeFechar());
      assertTrue(valR2Ativa.pendencias().stream().anyMatch(p -> "RODADA_ATIVA".equals(p.codigo())));

      // Contagem R2:
      // SKU-B: recontamos 20 -> Saldo era 20, fica CONFORME_APOS_RECONTAGEM (diff = 0)
      countingService.recordOccurrence(
          inv1Id, r2Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-B", 20, "DEPOSITO", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 2"
      );

      // SKU-C: recontamos 7 -> Saldo era 5, fica DIVERGENCIA_CONFIRMADA (sobra +2)
      countingService.recordOccurrence(
          inv1Id, r2Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-C", 7, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
          ), "Operador 2"
      );

      countingService.closeRound(inv1Id, r2Id, "281", false, "Supervisor 281");

      // 3. Investigar a divergência confirmada do SKU-C
      long apuracaoR2CId = findApuracaoId(scopedUrl, r2Id, "SKU-C");
      InventoryInvestigationService.InvestigationDetail invDetailC = investigationService.create(
          inv1Id, "281",
          new InventoryInvestigationService.CreateCommand(apuracaoR2CId, "AVARIA", "Sobraram 2 unidades após R2"),
          "Supervisor 281"
      );
      UUID invIdC = invDetailC.id();

      // Pré-validação com investigação PENDENTE
      InventoryClosingService.ValidationResult valInvPendente = closingService.validateClosing(inv1Id, "281");
      assertFalse(valInvPendente.podeFechar());
      assertTrue(valInvPendente.pendencias().stream().anyMatch(p -> "INVESTIGACOES_PENDENTES".equals(p.codigo())));

      // Concluir investigação
      investigationService.start(
          inv1Id, invIdC, "281",
          new InventoryInvestigationService.StartCommand("stock-1", "Conferente", "AVARIA", "Iniciando"),
          "Supervisor 281"
      );
      investigationService.addEvidence(
          inv1Id, invIdC, "281",
          new InventoryInvestigationService.AddEvidenceCommand("FOTO", "Foto das 2 unidades a mais", "ref-foto-123"),
          "Conferente"
      );
      investigationService.resolve(
          inv1Id, invIdC, "281",
          new InventoryInvestigationService.ResolveCommand("AVARIA", "Confirmada avaria e sobra física no depósito"),
          "Supervisor 281"
      );

      // Agora SKU-C está resolvido, SKU-B conforme, SKU-A conforme.
      // Resta apenas SKU-D não contado.
      InventoryClosingService.ValidationResult valAntesExcepcional = closingService.validateClosing(inv1Id, "281");
      assertFalse(valAntesExcepcional.podeFechar(), "Normal bloqueado por haver 1 item não contado");
      assertEquals(1, valAntesExcepcional.resumo().itensNaoContados());
      assertEquals(1, valAntesExcepcional.resumo().divergenciasConfirmadas());
      assertEquals(1, valAntesExcepcional.resumo().investigacoesResolvidas());
      assertEquals(0, valAntesExcepcional.resumo().investigacoesPendentes());

      // 4. Testar Fechamento Excepcional
      // Tentativa por usuário conferente sem role admin -> 403 Forbidden
      assertThrows(
          InventoryClosingService.ForbiddenException.class,
          () -> closingService.closeInventory(
              inv1Id, "281",
              new InventoryClosingService.CloseCommand("EXCEPCIONAL", "Justificativa válida com mais de quinze caracteres."),
              stockUser
          )
      );

      // Tentativa por admin com justificativa vazia ou < 15 chars -> 400 ValidationException
      assertThrows(
          InventoryClosingService.ValidationException.class,
          () -> closingService.closeInventory(
              inv1Id, "281",
              new InventoryClosingService.CloseCommand("EXCEPCIONAL", "Curto"),
              adminUser
          )
      );

      // Fechamento Excepcional com Sucesso pelo Admin
      String justificativaExcepcional = "Fechamento excepcional autorizado pela gerência devido a item descontinuado SKU-D.";
      InventoryClosingService.ClosingResult resultado1 = closingService.closeInventory(
          inv1Id, "281",
          new InventoryClosingService.CloseCommand("EXCEPCIONAL", justificativaExcepcional),
          adminUser
      );

      assertNotNull(resultado1);
      assertEquals("ENCERRADO", resultado1.inventarioStatus());
      assertEquals("EXCEPCIONAL", resultado1.tipoFechamento());
      assertEquals(justificativaExcepcional, resultado1.justificativaExcepcional());
      assertEquals(4, resultado1.totalItens());
      assertEquals(39, resultado1.totalUnidadesSnapshot()); // 10 + 20 + 5 + 4
      assertEquals(1, resultado1.itensConformesR1()); // SKU-A
      assertEquals(2, resultado1.itensEnviadosR2()); // SKU-B, SKU-C
      assertEquals(1, resultado1.itensConformesAposR2()); // SKU-B
      assertEquals(1, resultado1.divergenciasConfirmadas()); // SKU-C
      assertEquals(1, resultado1.investigacoesResolvidas()); // SKU-C
      assertEquals(1, resultado1.itensNaoContados()); // SKU-D
      assertEquals(0, resultado1.itensComFalta());
      assertEquals(1, resultado1.itensComSobra()); // SKU-C
      assertEquals(2, resultado1.quantidadeSobra()); // Sobra de 2 un no SKU-C
      assertEquals(0, resultado1.quantidadeFalta());
      assertNotNull(resultado1.pendenciasSnapshot());
      assertFalse(resultado1.pendenciasSnapshot().isEmpty());

      // 5. Testar Consulta de Histórico Detalhado
      InventoryClosingService.DetailedHistoryResult historico1 = closingService.getDetailedHistory(inv1Id, "281");
      assertNotNull(historico1);
      assertEquals(4, historico1.itens().size());

      // Verificar item por item no snapshot auditável
      InventoryClosingService.ItemAuditRecord snapA = findSnapshot(historico1.itens(), "SKU-A");
      assertEquals(10, snapA.saldoSnapshot());
      assertEquals(10, snapA.r1Fisico());
      assertEquals(0, snapA.r1Diferenca());
      assertEquals("CONFORME", snapA.r1Estado());
      assertFalse(snapA.houveR2());
      assertNull(snapA.r2Fisico());
      assertEquals(10, snapA.quantidadeFisicaFinal());
      assertEquals(0, snapA.diferencaFinal());
      assertEquals("CONFORME", snapA.estadoFinal());
      assertEquals("CONFORME", snapA.tipoDivergencia());

      InventoryClosingService.ItemAuditRecord snapB = findSnapshot(historico1.itens(), "SKU-B");
      assertEquals(20, snapB.saldoSnapshot());
      assertEquals(18, snapB.r1Fisico());
      assertEquals(-2, snapB.r1Diferenca());
      assertTrue(snapB.houveR2());
      assertEquals(20, snapB.r2Fisico());
      assertEquals(0, snapB.r2Diferenca());
      assertEquals("CONFORME_APOS_RECONTAGEM", snapB.r2Estado());
      assertEquals(20, snapB.quantidadeFisicaFinal());
      assertEquals(0, snapB.diferencaFinal());
      assertEquals("CONFORME_APOS_RECONTAGEM", snapB.estadoFinal());
      assertEquals("CONFORME", snapB.tipoDivergencia());

      InventoryClosingService.ItemAuditRecord snapC = findSnapshot(historico1.itens(), "SKU-C");
      assertEquals(5, snapC.saldoSnapshot());
      assertEquals(8, snapC.r1Fisico());
      assertEquals(3, snapC.r1Diferenca());
      assertTrue(snapC.houveR2());
      assertEquals(7, snapC.r2Fisico());
      assertEquals(2, snapC.r2Diferenca());
      assertEquals("DIVERGENCIA_CONFIRMADA", snapC.r2Estado());
      assertEquals(7, snapC.quantidadeFisicaFinal());
      assertEquals(2, snapC.diferencaFinal());
      assertEquals("DIVERGENCIA_CONFIRMADA", snapC.estadoFinal());
      assertEquals("SOBRA", snapC.tipoDivergencia());
      assertEquals("RESOLVIDA", snapC.statusInvestigacao());
      assertEquals("AVARIA", snapC.causaConfirmada());

      InventoryClosingService.ItemAuditRecord snapD = findSnapshot(historico1.itens(), "SKU-D");
      assertEquals(4, snapD.saldoSnapshot());
      assertNull(snapD.r1Fisico());
      assertNull(snapD.r1Diferenca());
      assertFalse(snapD.houveR2());
      assertNull(snapD.quantidadeFisicaFinal(), "Item não contado deve manter quantidade fisica null");
      assertNull(snapD.diferencaFinal(), "Item não contado deve manter diferenca null");
      assertEquals("NAO_CONTADO", snapD.estadoFinal());
      assertEquals("NAO_CONTADO", snapD.tipoDivergencia());

      // Verificar evento gravado na linha do tempo da sessão
      assertFalse(historico1.eventos().isEmpty());
      assertTrue(historico1.eventos().stream().anyMatch(e -> "INVENTARIO_ENCERRADO".equals(e.tipoEvento())));

      // =========================================================================
      // CENÁRIO 2: RIGOROSA IMUTABILIDADE PÓS-FECHAMENTO
      // =========================================================================
      // 1. Tentar registrar nova ocorrência de contagem -> 409
      assertThrows(
          InventoryCountingService.ConflictException.class,
          () -> countingService.recordOccurrence(
              inv1Id, r1Id, "281",
              new InventoryCountingService.RecordOccurrenceCommand(
                  "SKU-A", 12, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null
              ), "Operador 1"
          )
      );

      // 2. Tentar criar nova rodada de recontagem -> 409
      assertThrows(
          InventoryCountingService.ConflictException.class,
          () -> countingService.createRecountRound(
              inv1Id, "281", List.of(itemBId), "Supervisor 281"
          )
      );

      // 3. Tentar encerrar rodada já encerrada -> 409
      assertThrows(
          InventoryCountingService.ConflictException.class,
          () -> countingService.closeRound(
              inv1Id, r1Id, "281", false, "Supervisor 281"
          )
      );

      // 4. Tentar criar nova investigação -> 409
      assertThrows(
          InventoryInvestigationService.ConflictException.class,
          () -> investigationService.create(
              inv1Id, "281",
              new InventoryInvestigationService.CreateCommand(apuracaoR2CId, "AVARIA", "Tentativa indevida"),
              "Supervisor 281"
          )
      );

      // 5. Tentar adicionar evidência a investigação existente -> 409
      assertThrows(
          InventoryInvestigationService.ConflictException.class,
          () -> investigationService.addEvidence(
              inv1Id, invIdC, "281",
              new InventoryInvestigationService.AddEvidenceCommand("OBSERVACAO", "Tentativa apos fechamento", "ref-x"),
              "Supervisor 281"
          )
      );

      // 6. Tentar reabrir investigação existente -> 409
      assertThrows(
          InventoryInvestigationService.ConflictException.class,
          () -> investigationService.reopen(
              inv1Id, invIdC, "281",
              new InventoryInvestigationService.ReopenCommand("Reabertura pos fechamento nao permitida"),
              "Supervisor 281"
          )
      );

      // =========================================================================
      // CENÁRIO 3: INVENTÁRIO 2 - HAPPY PATH DE FECHAMENTO NORMAL
      // =========================================================================
      InventorySessionService.InventoryDetail inv2 = sessionService.create(
          new InventorySessionService.CreateCommand(
              seed.import281(), "281", "Inventário 100% Conforme", "GERAL", "NORMAL", List.of()
          ), "Supervisor 281"
      );
      long inv2Id = inv2.inventory().id();
      sessionService.open(inv2Id, "281", 0, "Supervisor 281");
      sessionService.start(inv2Id, "281", 1, "Supervisor 281");

      InventoryCountingService.RoundDetail r1Inv2 = countingService.getActiveRound(inv2Id, "281");
      long r1Inv2Id = r1Inv2.id();

      // Contar perfeitamente todos os 4 SKUs
      countingService.recordOccurrence(
          inv2Id, r1Inv2Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand("SKU-A", 10, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null),
          "Operador 1"
      );
      countingService.recordOccurrence(
          inv2Id, r1Inv2Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand("SKU-B", 20, "DEPOSITO", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null),
          "Operador 1"
      );
      countingService.recordOccurrence(
          inv2Id, r1Inv2Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand("SKU-C", 5, "VENDAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null),
          "Operador 1"
      );
      countingService.recordOccurrence(
          inv2Id, r1Inv2Id, "281",
          new InventoryCountingService.RecordOccurrenceCommand("SKU-D", 4, "TROCAS", "BOA", "DEFINIR", UUID.randomUUID(), "SCANNER", "PWA", Instant.now(), null),
          "Operador 1"
      );

      countingService.closeRound(inv2Id, r1Inv2Id, "281", false, "Supervisor 281");

      // Pré-validação deve aprovar fechamento normal
      InventoryClosingService.ValidationResult valNormal = closingService.validateClosing(inv2Id, "281");
      assertTrue(valNormal.podeFechar(), "Inventário com todos os itens conformes e apurados pode ser fechado normalmente");
      assertEquals(0, valNormal.pendencias().size());
      assertEquals(4, valNormal.resumo().itensConformes());
      assertEquals(0, valNormal.resumo().divergenciasConfirmadas());
      assertEquals(0, valNormal.resumo().itensNaoContados());

      // Fechamento Normal com Sucesso
      InventoryClosingService.ClosingResult resultadoNormal = closingService.closeInventory(
          inv2Id, "281",
          new InventoryClosingService.CloseCommand("NORMAL", null),
          stockUser
      );

      assertNotNull(resultadoNormal);
      assertEquals("ENCERRADO", resultadoNormal.inventarioStatus());
      assertEquals("NORMAL", resultadoNormal.tipoFechamento());
      assertEquals(4, resultadoNormal.totalItens());
      assertEquals(4, resultadoNormal.itensConformesR1());
      assertEquals(0, resultadoNormal.divergenciasConfirmadas());
      assertEquals(0, resultadoNormal.quantidadeFalta());
      assertEquals(0, resultadoNormal.quantidadeSobra());
      assertNull(resultadoNormal.justificativaExcepcional());

      // =========================================================================
      // CENÁRIO 4: ISOLAMENTO MULTI-FILIAL E FILTRAGEM DE HISTÓRICO
      // =========================================================================
      // Filial 282 não pode visualizar nem fechar inventário da Filial 281
      assertThrows(
          InventoryClosingService.NotFoundException.class,
          () -> closingService.validateClosing(inv2Id, "282")
      );
      assertThrows(
          InventoryClosingService.NotFoundException.class,
          () -> closingService.getResult(inv2Id, "282")
      );

      // Listagem com filtro de histórico/encerrados
      List<InventorySessionService.InventorySummary> encerrados281 = sessionService.list("281", "ENCERRADOS");
      assertTrue(encerrados281.stream().anyMatch(i -> i.id() == inv1Id));
      assertTrue(encerrados281.stream().anyMatch(i -> i.id() == inv2Id));

      List<InventorySessionService.InventorySummary> andamento281 = sessionService.list("281", "EM_ANDAMENTO");
      assertFalse(andamento281.stream().anyMatch(i -> i.id() == inv1Id), "Inventário encerrado não deve constar em andamento");
      assertFalse(andamento281.stream().anyMatch(i -> i.id() == inv2Id), "Inventário encerrado não deve constar em andamento");
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  private InventoryClosingService.ItemAuditRecord findSnapshot(List<InventoryClosingService.ItemAuditRecord> list, String sku) {
    return list.stream().filter(it -> sku.equals(it.sku())).findFirst()
        .orElseThrow(() -> new RuntimeException("SKU não encontrado no snapshot: " + sku));
  }

  private String authorizedDatabaseUrl() {
    String databaseUrl = System.getenv("DATABASE_URL");
    boolean allowed = Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"));
    assumeFalse(!allowed || databaseUrl == null || databaseUrl.isBlank(),
        "Banco PostgreSQL descartável não autorizado; teste de fechamento ignorado.");
    return databaseUrl;
  }

  private Connection connect(DatabaseUrlParser.JdbcConfig config) throws Exception {
    return config.username().isBlank()
        ? DriverManager.getConnection(config.url())
        : DriverManager.getConnection(config.url(), config.username(), config.password());
  }

  private String withCurrentSchema(String databaseUrl, String schema) {
    return databaseUrl + (databaseUrl.contains("?") ? "&" : "?")
        + "currentSchema=" + schema
        + "&options=-c%20search_path=" + schema + ",public";
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

  private Seed seedData(String databaseUrl, String schema) throws Exception {
    DatabaseUrlParser.JdbcConfig cfg = DatabaseUrlParser.parse(databaseUrl);
    try (Connection conn = DriverManager.getConnection(cfg.url(), cfg.username(), cfg.password());
         Statement st = conn.createStatement()) {
      st.execute("SET search_path TO " + schema + ", public");
      long b281 = scalarLong(st, "SELECT id FROM filiais WHERE codigo = '281'");
      st.execute("INSERT INTO filiais (codigo, nome, ativa) VALUES ('282', 'Filial 282', true) ON CONFLICT DO NOTHING");
      long b282 = scalarLong(st, "SELECT id FROM filiais WHERE codigo = '282'");

      long imp281 = scalarLong(st, "INSERT INTO importacoes_saldo (nome_arquivo, quantidade_skus, filial_id) VALUES ('saldo-281.pdf', 4, " + b281 + ") RETURNING id");
      long imp282 = scalarLong(st, "INSERT INTO importacoes_saldo (nome_arquivo, quantidade_skus, filial_id) VALUES ('saldo-282.pdf', 1, " + b282 + ") RETURNING id");

      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-A', 10)");
      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-B', 20)");
      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-C', 5)");
      st.execute("INSERT INTO saldos (importacao_id, sku, saldo) VALUES (" + imp281 + ", 'SKU-D', 4)");

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
