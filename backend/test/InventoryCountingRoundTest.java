package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class InventoryCountingRoundTest {

  @Test
  void validatesItemProjectionSemanticsWithoutDatabase() {
    Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-24T10:01:00Z");
    Instant t3 = Instant.parse("2026-09-24T10:02:00Z");
    Instant t4 = Instant.parse("2026-09-24T10:03:00Z");
    Instant t5 = Instant.parse("2026-09-24T10:04:00Z");

    // 1. Vazio
    var projEmpty = InventoryCountingService.calculateItemProjection(List.of());
    assertEquals(0, projEmpty.totalQuantity());
    assertNull(projEmpty.lastOccurrenceId());

    // 2. DEFINIR 10
    var occ1 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-X", 10, "BOA", "DEFINIR", "Operador 1", UUID.randomUUID(), "SCANNER", "", t1, t1, null);
    var proj1 = InventoryCountingService.calculateItemProjection(List.of(occ1));
    assertEquals(10, proj1.totalQuantity());
    assertEquals(10, proj1.categoryQuantities().get("BOA"));
    assertEquals(1L, proj1.lastOccurrenceId());

    // 3. SOMAR +5 -> 15
    var occ2 = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-X", 5, "BOA", "SOMAR", "Operador 1", UUID.randomUUID(), "SCANNER", "", t2, t2, null);
    var proj2 = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2));
    assertEquals(15, proj2.totalQuantity());
    assertEquals(2L, proj2.lastOccurrenceId());

    // 4. CORRECAO 12 -> 12
    var occ3 = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-X", 12, "BOA", "CORRECAO", "Supervisor", UUID.randomUUID(), "MANUAL", "", t3, t3, 2L);
    var proj3 = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, occ3));
    assertEquals(12, proj3.totalQuantity());
    assertEquals(3L, proj3.lastOccurrenceId());

    // 5. SOMAR +4 -> 16
    var occ4 = new InventoryCountingService.OccurrenceRecord(
        4L, 100L, 10L, "SKU-X", 4, "BOA", "SOMAR", "Operador 1", UUID.randomUUID(), "SCANNER", "", t4, t4, null);
    var proj4 = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, occ3, occ4));
    assertEquals(16, proj4.totalQuantity());
    assertEquals(4L, proj4.lastOccurrenceId());

    // 6. Múltiplas Categorias (BOA: 16, AVARIA: 3)
    var occ5 = new InventoryCountingService.OccurrenceRecord(
        5L, 100L, 10L, "SKU-X", 3, "AVARIA", "DEFINIR", "Operador 2", UUID.randomUUID(), "SCANNER", "", t5, t5, null);
    var proj5 = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, occ3, occ4, occ5));
    assertEquals(19, proj5.totalQuantity(), "16 BOA + 3 AVARIA = 19");
    assertEquals(16, proj5.categoryQuantities().get("BOA"));
    assertEquals(3, proj5.categoryQuantities().get("AVARIA"));
    assertEquals(0, proj5.categoryQuantities().get("ASSISTENCIA"));
    assertEquals(5L, proj5.lastOccurrenceId());
  }

  @Test
  void validatesCompleteCountingLifecycleAndAuditability() throws Exception {
    String databaseUrl = authorizedDatabaseUrl();
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    String schema = "mncheck_counting_" + UUID.randomUUID().toString().replace("-", "");

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
      InventorySessionService sessionService = new InventorySessionService(scopedUrl);
      InventoryCountingService countingService = new InventoryCountingService(scopedUrl);

      // 1. Cria inventário NORMAL e CEGO
      InventorySessionService.InventoryDetail normalDetail = sessionService.create(
          new InventorySessionService.CreateCommand(seed.import281(), "281", "Inventário Normal",
              "GERAL", "NORMAL", List.of()), "Gestor");
      long normalId = normalDetail.inventory().id();

      InventorySessionService.InventoryDetail cegoDetail = sessionService.create(
          new InventorySessionService.CreateCommand(seed.import281(), "281", "Inventário Cego",
              "PARCIAL", "CEGO", List.of("SKU-100", "SKU-200")), "Gestor");
      long cegoId = cegoDetail.inventory().id();

      // 2. GET rodada ativa antes de iniciar não cria rodada e retorna 404
      assertThrows(InventoryCountingService.NotFoundException.class,
          () -> countingService.getActiveRound(normalId, "281"));
      assertEquals(0, countRows(scopedUrl, "rodadas_contagem"));

      // 3. Abre e Inicia Inventário Normal -> Transição e Rodada 1 criadas atomicamente
      sessionService.open(normalId, "281", 0, "Supervisor");
      InventorySessionService.InventoryDetail started = sessionService.start(normalId, "281", 1, "Supervisor");
      assertEquals("EM_CONTAGEM", started.inventory().status());
      assertEquals(2, started.inventory().version());

      InventoryCountingService.RoundDetail activeRound = countingService.getActiveRound(normalId, "281");
      assertNotNull(activeRound);
      assertEquals(1, activeRound.numero());
      assertEquals("CONTAGEM", activeRound.tipo());
      assertEquals("EM_ANDAMENTO", activeRound.status());
      assertEquals("Supervisor", activeRound.iniciadaPor());
      assertEquals(1, countRows(scopedUrl, "rodadas_contagem"));

      // 4. GET subsequente não cria novas rodadas
      countingService.getActiveRound(normalId, "281");
      assertEquals(1, countRows(scopedUrl, "rodadas_contagem"));

      // 5. Sanitização de Modo CEGO: GET /api/inventarios/{id} e itens
      InventorySessionService.InventoryDetail loadedCegoDetail = sessionService.loadDetail(cegoId, "281");
      for (InventorySessionService.InventoryItem item : loadedCegoDetail.items()) {
        assertNull(item.saldoSnapshot(), "Em modo CEGO, saldoSnapshot deve ser null no detalhe da sessão");
      }

      sessionService.open(cegoId, "281", 0, "Supervisor");
      sessionService.start(cegoId, "281", 1, "Supervisor");
      InventoryCountingService.ItemListResult cegoItems = countingService.listItems(cegoId, "281", null, null);
      for (InventoryCountingService.CountingItem item : cegoItems.itens()) {
        assertNull(item.saldoSnapshot(), "Em modo CEGO, saldoSnapshot deve ser null na listagem operacional");
      }

      // 6. Sessão NORMAL expõe saldoSnapshot
      InventoryCountingService.ItemListResult normalItems = countingService.listItems(normalId, "281", null, null);
      for (InventoryCountingService.CountingItem item : normalItems.itens()) {
        assertNotNull(item.saldoSnapshot(), "Em modo NORMAL, saldoSnapshot deve estar presente");
      }

      // 7. Registro de contagem: DEFINIR base
      UUID event1 = UUID.randomUUID();
      InventoryCountingService.OccurrenceResult occ1 = countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 10, "BOA", "DEFINIR", event1, "SCANNER", "Terminal 01", Instant.now(), null
          ), "Operador 01"
      );
      assertEquals(10, occ1.quantidadeItemProjetada());
      assertEquals(10, occ1.categoriasItem().get("BOA"));
      assertEquals("Operador 01", occ1.operador());

      // 8. Idempotência: Mesma chamada com mesmo client_event_id não duplica e retorna mesmo ID
      InventoryCountingService.OccurrenceResult occ1Dupe = countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 10, "BOA", "DEFINIR", event1, "SCANNER", "Terminal 01", Instant.now(), null
          ), "Operador 01"
      );
      assertEquals(occ1.id(), occ1Dupe.id());
      assertEquals(1, countRows(scopedUrl, "ocorrencias_contagem"));

      // 9. Semântica de Múltiplas Ocorrências: SOMAR cumulativo
      UUID event2 = UUID.randomUUID();
      InventoryCountingService.OccurrenceResult occ2 = countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 5, "BOA", "SOMAR", event2, "SCANNER", "Terminal 01", Instant.now(), null
          ), "Operador 01"
      );
      assertEquals(15, occ2.quantidadeItemProjetada(), "10 + 5 = 15");
      assertEquals(2, countRows(scopedUrl, "ocorrencias_contagem"));

      // 10. Semântica de Correção: CORRECAO ajusta valor e referencia ocorrência anterior
      UUID event3 = UUID.randomUUID();
      InventoryCountingService.OccurrenceResult occ3 = countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 12, "BOA", "CORRECAO", event3, "MANUAL", "Terminal 01", Instant.now(), occ2.id()
          ), "Supervisor"
      );
      assertEquals(12, occ3.quantidadeItemProjetada(), "CORRECAO ajusta o valor total para 12");
      assertEquals(occ2.id(), occ3.referenciaId());
      assertEquals(3, countRows(scopedUrl, "ocorrencias_contagem"), "Todas as 3 ocorrências são imutáveis");

      // 11. SOMAR após CORRECAO soma à base corrigida
      UUID event4 = UUID.randomUUID();
      InventoryCountingService.OccurrenceResult occ4 = countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 3, "BOA", "SOMAR", event4, "SCANNER", "Terminal 01", Instant.now(), null
          ), "Operador 01"
      );
      assertEquals(15, occ4.quantidadeItemProjetada(), "12 + 3 = 15");

      // 12. Progresso baseado em ocorrencias_contagem e filtros de estado
      // SKU-100 foi contado, SKU-200 e SKU-300 estão pendentes
      InventoryCountingService.ItemListResult itemsState = countingService.listItems(normalId, "281", null, null);
      assertEquals(3, itemsState.progresso().totalSkus());
      assertEquals(1, itemsState.progresso().contados());
      assertEquals(2, itemsState.progresso().pendentes());
      assertEquals(33, itemsState.progresso().percentual());

      InventoryCountingService.ItemListResult pendentes = countingService.listItems(normalId, "281", null, "PENDENTE");
      assertEquals(2, pendentes.itens().size());

      InventoryCountingService.ItemListResult contados = countingService.listItems(normalId, "281", null, "CONTADO");
      assertEquals(1, contados.itens().size());
      assertEquals("SKU-100", contados.itens().get(0).sku());

      // 13. Isolamento multi-filial: filial 282 não acessa inventário da 281
      assertThrows(InventoryCountingService.NotFoundException.class,
          () -> countingService.getActiveRound(normalId, "282"));
      assertThrows(InventoryCountingService.NotFoundException.class,
          () -> countingService.listItems(normalId, "282", null, null));
      assertThrows(InventoryCountingService.NotFoundException.class,
          () -> countingService.recordOccurrence(normalId, activeRound.id(), "282",
              new InventoryCountingService.RecordOccurrenceCommand(
                  "SKU-100", 1, "BOA", "SOMAR", UUID.randomUUID(), "SCANNER", "", null, null
              ), "Operador"));

      // 14. Integridade e Proteção contra deleção acidental (ON DELETE RESTRICT)
      try (Connection conn = connect(config); Statement stmt = conn.createStatement()) {
        stmt.execute("SET search_path TO " + schema);
        // Tentar deletar inventário com rodadas e ocorrências deve falhar por RESTRICT
        SQLException delEx = assertThrows(SQLException.class, () -> stmt.execute("DELETE FROM inventarios WHERE id = " + normalId));
        assertTrue(delEx.getMessage().toLowerCase().contains("violates foreign key constraint")
            || delEx.getSQLState().startsWith("23"), "RESTRICT deve impedir DELETE silencioso");

        // Tentar deletar rodada com ocorrências deve falhar por RESTRICT
        SQLException delRoundEx = assertThrows(SQLException.class, () -> stmt.execute("DELETE FROM rodadas_contagem WHERE id = " + activeRound.id()));
        assertTrue(delRoundEx.getSQLState().startsWith("23"));
      }

      // 15. Validações de entrada
      assertThrows(InventoryCountingService.ValidationException.class, () -> countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", -1, "BOA", "SOMAR", UUID.randomUUID(), "SCANNER", "", null, null
          ), "Operador"));

      assertThrows(InventoryCountingService.ValidationException.class, () -> countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 1, "CATEGORIA_INVALIDA", "SOMAR", UUID.randomUUID(), "SCANNER", "", null, null
          ), "Operador"));

      assertThrows(InventoryCountingService.ValidationException.class, () -> countingService.recordOccurrence(
          normalId, activeRound.id(), "281",
          new InventoryCountingService.RecordOccurrenceCommand(
              "SKU-100", 1, "BOA", "ACAO_INVALIDA", UUID.randomUUID(), "SCANNER", "", null, null
          ), "Operador"));
    } finally {
      try (Connection connection = connect(config); Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }
  }

  private int countRows(String scopedUrl, String tableName) throws Exception {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(scopedUrl);
    try (Connection connection = connect(config);
         Statement statement = connection.createStatement()) {
      int idx = scopedUrl.indexOf("currentSchema=");
      if (idx >= 0) {
        String sc = scopedUrl.substring(idx + "currentSchema=".length());
        int amp = sc.indexOf('&');
        if (amp >= 0) sc = sc.substring(0, amp);
        statement.execute("SET search_path TO " + sc + ", public");
      }
      try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private Seed seed(String databaseUrl) throws Exception {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
    try (Connection connection = connect(config);
         Statement st = connection.createStatement()) {
      int idx = databaseUrl.indexOf("currentSchema=");
      if (idx >= 0) {
        String sc = databaseUrl.substring(idx + "currentSchema=".length());
        int amp = sc.indexOf('&');
        if (amp >= 0) sc = sc.substring(0, amp);
        st.execute("SET search_path TO " + sc + ", public");
      }
      long branch281 = scalarId(connection, "SELECT id FROM filiais WHERE codigo = '281'");
      long branch282;
      try (PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO filiais (codigo, nome, ativa) VALUES ('282', 'Filial 282', TRUE)
          RETURNING id
          """)) {
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          branch282 = result.getLong(1);
        }
      }
      long import281 = insertImport(connection, branch281, "saldo-281.pdf");
      insertBalance(connection, branch281, import281, "SKU-100", "Produto 100", 10);
      insertBalance(connection, branch281, import281, "SKU-200", "Produto 200", 20);
      insertBalance(connection, branch281, import281, "SKU-300", "Produto 300", 30);
      return new Seed(import281, branch282);
    }
  }

  private long insertImport(Connection connection, long branchId, String fileName) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO importacoes_saldo (nome_arquivo, importado_por, quantidade_skus, filial_id)
        VALUES (?, 'Sistema', 3, ?)
        RETURNING id
        """)) {
      statement.setString(1, fileName);
      statement.setLong(2, branchId);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private void insertBalance(Connection connection, long branchId, long importId, String sku,
      String description, int balance) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO saldos (sku, saldo, importacao_id) VALUES (?, ?, ?)
        """)) {
      statement.setString(1, sku);
      statement.setInt(2, balance);
      statement.setLong(3, importId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO estoque_produtos
          (filial_id, sku, descricao, saldo_sistema, saldo_contado, saldo_assistencia, saldo_avaria, saldo_outros, ativo, importacao_id)
        VALUES (?, ?, ?, ?, 0, 0, 0, 0, TRUE, ?)
        ON CONFLICT (filial_id, sku) DO NOTHING
        """)) {
      statement.setLong(1, branchId);
      statement.setString(2, sku);
      statement.setString(3, description);
      statement.setInt(4, balance);
      statement.setLong(5, importId);
      statement.executeUpdate();
    }
  }

  private long scalarId(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private String authorizedDatabaseUrl() {
    String databaseUrl = System.getenv("DATABASE_URL");
    boolean allowed = Boolean.parseBoolean(System.getenv("MN_CHECK_ALLOW_DATABASE_TESTS"));
    assumeFalse(!allowed || databaseUrl == null || databaseUrl.isBlank(),
        "Banco PostgreSQL descartável não autorizado; teste de contagem ignorado.");
    return databaseUrl;
  }

  private Connection connect(DatabaseUrlParser.JdbcConfig config) throws SQLException {
    return config.username().isBlank() ? DriverManager.getConnection(config.url())
        : DriverManager.getConnection(config.url(), config.username(), config.password());
  }

  private String withCurrentSchema(String databaseUrl, String schema) {
    return databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema
        + "&options=-c%20search_path%3D" + schema + ",public";
  }

  private record Seed(long import281, long branch282Id) {}
}
