package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PostgresDatabaseTest {
  private String databaseUrl;

  @BeforeEach
  void prepareDatabase() {
    databaseUrl = System.getenv("DATABASE_URL");
    assertFalse(databaseUrl == null || databaseUrl.isBlank(), "DATABASE_URL deve existir no teste");
  }

  @Test
  void persistsImportsCountsAndHistoryAcrossConnections() throws Exception {
    PostgresDatabase firstConnection = new PostgresDatabase(databaseUrl);
    String identity = firstConnection.testConnection();
    assertFalse(identity.isBlank());

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String firstSku = "TESTE-" + suffix + ".1";
    String secondSku = "TESTE-" + suffix + ".2";
    PostgresDatabase.ImportSummary imported = firstConnection.saveBalanceImport(
        "teste-neon-" + suffix + ".pdf",
        "Teste automatizado",
        List.of(
            new PostgresDatabase.BalanceRow(firstSku, 406),
            new PostgresDatabase.BalanceRow(secondSku, 108)
        ),
        5,
        269,
        27,
        0,
        0
    );
    assertEquals(2, imported.skuCount());

    long countId = firstConnection.saveCount(
        "Teste automatizado " + suffix,
        List.of(
            new PostgresDatabase.CountRow(firstSku, 406, 405),
            new PostgresDatabase.CountRow(secondSku, 108, 108)
        )
    );
    assertTrue(countId > 0);

    PostgresDatabase afterRestart = new PostgresDatabase(databaseUrl);
    PostgresDatabase.BalanceSnapshot snapshot = afterRestart.loadLatestBalances();
    assertEquals("teste-neon-" + suffix + ".pdf", snapshot.importSummary().fileName());
    assertEquals(2, snapshot.rows().size());
    assertEquals(405, countedOf(snapshot, firstSku));
    assertEquals(108, countedOf(snapshot, secondSku));

    PostgresDatabase.ImportSummary reimported = afterRestart.saveBalanceImport(
        "teste-neon-atualizado-" + suffix + ".pdf",
        "Teste automatizado",
        List.of(new PostgresDatabase.BalanceRow(firstSku, 410)),
        1,
        10,
        1,
        0,
        0
    );
    PostgresDatabase.BalanceSnapshot afterReimport = afterRestart.loadLatestBalances();
    assertEquals(1, afterReimport.rows().size(), "produto removido deve ficar inativo");
    assertEquals(405, countedOf(afterReimport, firstSku), "nova importação deve preservar a contagem");
    assertEquals(410, afterReimport.rows().get(0).systemBalance(), "saldo base deve ser atualizado");

    String manualSku = "76331.3.4";
    PostgresDatabase.ImportSummary manual = afterRestart.saveManualBalanceProduct(
        manualSku,
        112,
        111,
        "Teste automatizado"
    );
    PostgresDatabase.BalanceSnapshot afterManual = afterRestart.loadLatestBalances();
    assertEquals(2, afterManual.rows().size(), "produto manual deve entrar no saldo ativo");
    assertEquals(111, countedOf(afterManual, manualSku));

    List<PostgresDatabase.HistoryEntry> history = afterRestart.loadHistory();
    assertTrue(history.stream().anyMatch(item ->
        item.action().equals("count_upload") && item.description().contains(suffix)));
    assertTrue(history.stream().anyMatch(item ->
        item.action().equals("update_counts") && item.operator().contains(suffix)));

    PostgresDatabase.ScanHistoryEntry scan = firstConnection.saveScanHistory(
        "mapa-" + suffix,
        "Teste automatizado",
        "74266.1.3",
        "74266.1.2",
        false,
        "Voltagem incorreta",
        "scanner"
    );
    List<PostgresDatabase.ScanHistoryEntry> scanHistory =
        afterRestart.loadScanHistory("mapa-" + suffix, 10);
    assertEquals(1, scanHistory.size());
    assertEquals(scan.id(), scanHistory.get(0).id());
    assertEquals("Voltagem incorreta", scanHistory.get(0).reason());

    PostgresDatabase.ConferenceSession conference = firstConnection.saveConferenceProgress(
        "mapa-" + suffix,
        "Teste automatizado",
        firstSku,
        3,
        5
    );
    assertEquals("EM_ANDAMENTO", conference.status());
    assertEquals(3, conference.items().get(0).checkedQuantity());
    assertEquals("PAUSADA", afterRestart.changeConferenceStatus(
        "mapa-" + suffix, "Teste automatizado", "PAUSADA"
    ).status());
    assertEquals(3, new PostgresDatabase(databaseUrl)
        .loadConferenceSession("mapa-" + suffix).items().get(0).checkedQuantity());

    System.out.println("NEON_TEST conexão=" + afterRestart.testConnection()
        + " importacao_id=" + imported.id()
        + " skus=" + snapshot.rows().size()
        + " contagem_id=" + countId
        + " historico_total=" + history.size()
        + " leituras_scanner=" + scanHistory.size());

    cleanup(firstConnection, List.of(imported.id(), reimported.id(), manual.id()), countId, "mapa-" + suffix);
  }

  private int countedOf(PostgresDatabase.BalanceSnapshot snapshot, String sku) {
    return snapshot.rows().stream()
        .filter(row -> row.sku().equals(sku))
        .findFirst()
        .orElseThrow()
        .countedQuantity();
  }

  private void cleanup(
      PostgresDatabase database,
      List<Long> importIds,
      long countId,
      String mapId
  ) throws Exception {
    try (Connection connection = database.connect()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM historico_scanner WHERE mapa_id = ?"
      )) {
        statement.setString(1, mapId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM conferencias WHERE mapa_id = ?"
      )) {
        statement.setString(1, mapId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement("DELETE FROM contagens WHERE id = ?")) {
        statement.setLong(1, countId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM estoque_produtos WHERE importacao_id = ANY (?)"
      )) {
        statement.setArray(1, connection.createArrayOf("bigint", importIds.toArray()));
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM importacoes_saldo WHERE id = ANY (?)"
      )) {
        statement.setArray(1, connection.createArrayOf("bigint", importIds.toArray()));
        statement.executeUpdate();
      }
    }
  }
}
