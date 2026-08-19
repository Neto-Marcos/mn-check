package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EnterpriseServiceIntegrationTest {
  private static final UUID MAIN_BRANCH = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final MmCheckServer.SessionPrincipal ADMIN =
      new MmCheckServer.SessionPrincipal("admin-id", "Marcos", "Marcos", "admin");
  private static EmbeddedPostgres postgres;
  private static EnterpriseService service;

  @BeforeAll
  static void startPostgres() throws Exception {
    postgres = EmbeddedPostgres.builder().start();
    service = new EnterpriseService(new EnterpriseDatabase(
        postgres.getJdbcUrl("postgres", "postgres") + "&sslmode=disable"));
  }

  @AfterAll
  static void stopPostgres() throws Exception {
    if (postgres != null) postgres.close();
  }

  @Test
  void completeFlowRemainsAtomicIdempotentAndReconciled() throws Exception {
    Map<String, Object> product = service.saveProduct(ADMIN, Map.of(
        "sku", "SKU-001", "internalCode", "INT-001", "description", "Produto de teste",
        "eans", List.of("7891234567890", "7891234567891")), "product-1");
    assertNotNull(product.get("id"));
    Map<String, Object> printer = service.createPrinter(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "name", "Zebra Teste", "manufacturer", "ZEBRA",
        "widthMm", 80, "heightMm", 50, "dpi", 300), "printer-create-1");
    Map<String, Object> printJob = service.createPrintJob(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "printerId", printer.get("id").toString(),
        "productId", product.get("id").toString(), "originId", product.get("id").toString(),
        "originType", "TESTE", "labels", 2), "print-job-1");
    Map<String, Object> repeatedPrintJob = service.createPrintJob(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "printerId", printer.get("id").toString(),
        "productId", product.get("id").toString(), "originId", product.get("id").toString(),
        "originType", "TESTE", "labels", 2), "print-job-1");
    assertEquals(printJob.get("jobId"), repeatedPrintJob.get("jobId"));
    assertEquals(80, ((Number) printJob.get("widthMm")).intValue());
    assertEquals(50, ((Number) printJob.get("heightMm")).intValue());
    Map<String, Object> parameter = service.saveParameter(ADMIN, "operacao.fila_offline_limite", Map.of(
        "branchId", MAIN_BRANCH.toString(), "value", 500), "parameter-save-1");
    Map<String, Object> repeatedParameter = service.saveParameter(ADMIN, "operacao.fila_offline_limite", Map.of(
        "branchId", MAIN_BRANCH.toString(), "value", 999), "parameter-save-1");
    assertEquals("500", parameter.get("valor"));
    assertEquals(parameter.get("valor"), repeatedParameter.get("valor"));

    service.applyCount(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "opening", true, "countReference", "ABERTURA-TESTE",
        "items", List.of(Map.of("code", "INT-001", "quantity", 100))), "count-opening-1");
    assertBalance(MAIN_BRANCH, 100, 100, 0, 0, 0);

    Map<String, Object> contenderA = createMap("MAPA-CONCORRENTE-A", "a".repeat(64), 70, "map-create-a");
    Map<String, Object> contenderB = createMap("MAPA-CONCORRENTE-B", "b".repeat(64), 70, "map-create-b");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first = pool.submit(() -> publishAfter(start, id(contenderA), "publish-a"));
      Future<Boolean> second = pool.submit(() -> publishAfter(start, id(contenderB), "publish-b"));
      start.countDown();
      boolean firstPublished = first.get();
      boolean secondPublished = second.get();
      assertTrue(firstPublished ^ secondPublished, "Somente uma reserva concorrente pode vencer.");
      UUID winner = firstPublished ? id(contenderA) : id(contenderB);
      service.cancelMap(ADMIN, winner, "Ensaio de concorrência concluído", "cancel-winner");
    } finally {
      pool.shutdownNow();
    }
    assertBalance(MAIN_BRANCH, 100, 100, 0, 0, 0);

    Map<String, Object> map = createMap("MAPA-OFICIAL", "c".repeat(64), 40, "map-create-main");
    UUID mapId = id(map);
    service.publishMap(ADMIN, mapId, "publish-main");
    service.publishMap(ADMIN, mapId, "publish-main");
    assertBalance(MAIN_BRANCH, 100, 60, 40, 0, 0);
    service.scanMap(ADMIN, mapId, Map.of("code", "INT-001", "quantity", 35), false, "pick-1");
    service.authorizeMapShortage(ADMIN, mapId,
        Map.of("stage", "PICKING", "reason", "Falta física confirmada pelo supervisor"), "authorize-pick-shortage");
    assertBalance(MAIN_BRANCH, 100, 65, 35, 0, 0);
    service.finishPicking(ADMIN, mapId, "finish-pick-1");
    service.scanMap(ADMIN, mapId, Map.of("code", "7891234567891", "quantity", 30), true, "conference-1");
    service.authorizeMapShortage(ADMIN, mapId,
        Map.of("stage", "CONFERENCE", "reason", "Falta confirmada na reconferência"), "authorize-conference-shortage");
    assertBalance(MAIN_BRANCH, 100, 70, 30, 0, 0);
    service.dispatchMap(ADMIN, mapId, "dispatch-1");
    assertBalance(MAIN_BRANCH, 70, 70, 0, 0, 0);

    Map<String, Object> receipt = service.createReceipt(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "reference", "CARGA-TESTE"), "receipt-create-1");
    UUID receiptId = id(receipt);
    service.importNfe(ADMIN, receiptId, nfe("1".repeat(44), 10), "nfe-import-1");
    service.importNfe(ADMIN, receiptId, nfe("2".repeat(44), 5), "nfe-import-2");
    Map<String, Object> duplicateReceipt = service.createReceipt(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "reference", "CARGA-DUPLICADA"), "receipt-create-duplicate");
    try {
      service.importNfe(ADMIN, id(duplicateReceipt), nfe("1".repeat(44), 10), "nfe-import-duplicate");
      throw new AssertionError("Uma NF-e repetida deveria ser bloqueada.");
    } catch (EnterpriseDatabase.EnterpriseException expected) {
      assertEquals(409, expected.status());
    }
    service.scanReceipt(ADMIN, receiptId,
        Map.of("code", "7891234567890", "quantity", 13, "deviceId", "coletor-01"), "receipt-scan-regular");
    service.registerDamage(ADMIN, receiptId,
        Map.of("code", "7891234567891", "quantity", 2, "reason", "Embalagem danificada na descarga"),
        "receipt-damage-1");
    Map<String, Object> scanned = service.scanReceipt(ADMIN, receiptId,
        Map.of("code", "7891234567890", "quantity", 2, "deviceId", "coletor-01"), "receipt-scan-excess");
    Map<String, Object> repeated = service.scanReceipt(ADMIN, receiptId,
        Map.of("code", "7891234567890", "quantity", 2, "deviceId", "coletor-01"), "receipt-scan-excess");
    assertEquals(total(scanned, "quantidade_recebida"), total(repeated, "quantidade_recebida"));
    assertEquals(13, total(scanned, "quantidade_recebida"));
    assertEquals(2, total(scanned, "quantidade_avariada"));
    assertEquals(2, total(scanned, "quantidade_quarentena"));
    service.finalizeReceipt(ADMIN, receiptId, "receipt-finalize-1");
    assertBalance(MAIN_BRANCH, 87, 83, 0, 0, 4);

    Map<String, Object> receiptDetails = service.receiptDetails(ADMIN, receiptId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> exceptions = (List<Map<String, Object>>) receiptDetails.get("exceptions");
    Map<String, Object> damage = exceptions.stream()
        .filter(item -> "AVARIA".equals(item.get("tipo"))).findFirst().orElseThrow();
    service.resolveException(ADMIN, id(damage),
        Map.of("resolution", "LIBERAR_ESTOQUE", "reason", "Avaria apenas na embalagem"), "resolve-damage-1");
    assertBalance(MAIN_BRANCH, 87, 85, 0, 0, 2);
    Map<String, Object> excess = exceptions.stream()
        .filter(item -> "EXCESSO".equals(item.get("tipo"))).findFirst().orElseThrow();
    service.resolveException(ADMIN, id(excess),
        Map.of("resolution", "LIBERAR_ESTOQUE", "reason", "Mercadoria aceita pelo supervisor"), "resolve-excess-1");
    assertBalance(MAIN_BRANCH, 87, 87, 0, 0, 0);

    Map<String, Object> destination = service.createBranch(ADMIN,
        Map.of("code", "FILIAL-TESTE", "name", "Filial Teste"), "branch-create-1");
    UUID destinationId = id(destination);
    Map<String, Object> transfer = service.createTransfer(ADMIN, Map.of(
        "originBranchId", MAIN_BRANCH.toString(), "destinationBranchId", destinationId.toString(),
        "reference", "TRF-TESTE", "items", List.of(Map.of("code", "INT-001", "quantity", 5))),
        "transfer-create-1");
    UUID transferId = id(transfer);
    service.approveTransfer(ADMIN, transferId, "transfer-approve-1");
    service.shipTransfer(ADMIN, transferId, "transfer-ship-1");
    Map<String, Object> transferDetails = service.transferDetails(ADMIN, transferId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> transferItems = (List<Map<String, Object>>) transferDetails.get("items");
    service.receiveTransfer(ADMIN, transferId, Map.of("items", List.of(Map.of(
        "itemId", transferItems.get(0).get("id").toString(), "quantity", 5))), "transfer-receive-1");
    assertBalance(MAIN_BRANCH, 82, 82, 0, 0, 0);
    assertBalance(destinationId, 5, 5, 0, 0, 0);

    Map<String, Object> canceledTransfer = createTransfer(destinationId, "TRF-CANCELADA", 6, "transfer-create-cancel");
    service.approveTransfer(ADMIN, id(canceledTransfer), "transfer-approve-cancel");
    service.cancelTransfer(ADMIN, id(canceledTransfer), "Solicitação cancelada em teste", "transfer-cancel-1");
    assertBalance(MAIN_BRANCH, 82, 82, 0, 0, 0);

    Map<String, Object> divergentTransfer = createTransfer(destinationId, "TRF-EXCESSO", 4, "transfer-create-excess");
    UUID divergentTransferId = id(divergentTransfer);
    service.approveTransfer(ADMIN, divergentTransferId, "transfer-approve-excess");
    service.shipTransfer(ADMIN, divergentTransferId, "transfer-ship-excess");
    Map<String, Object> divergentDetails = service.transferDetails(ADMIN, divergentTransferId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> divergentItems = (List<Map<String, Object>>) divergentDetails.get("items");
    service.receiveTransfer(ADMIN, divergentTransferId, Map.of("items", List.of(Map.of(
        "itemId", divergentItems.get(0).get("id").toString(), "quantity", 5))), "transfer-receive-excess");
    assertBalance(MAIN_BRANCH, 78, 78, 0, 0, 0);
    assertBalance(destinationId, 10, 9, 0, 0, 1);
    divergentDetails = service.transferDetails(ADMIN, divergentTransferId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> divergentExceptions = (List<Map<String, Object>>) divergentDetails.get("exceptions");
    service.resolveException(ADMIN, id(divergentExceptions.get(0)),
        Map.of("resolution", "DESCARTAR", "reason", "Volume excedente sem documento"), "resolve-transfer-excess");
    assertBalance(destinationId, 9, 9, 0, 0, 0);

    Map<String, Object> shortTransfer = createTransfer(destinationId, "TRF-FALTA", 3, "transfer-create-short");
    UUID shortTransferId = id(shortTransfer);
    service.approveTransfer(ADMIN, shortTransferId, "transfer-approve-short");
    service.shipTransfer(ADMIN, shortTransferId, "transfer-ship-short");
    Map<String, Object> shortDetails = service.transferDetails(ADMIN, shortTransferId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shortItems = (List<Map<String, Object>>) shortDetails.get("items");
    service.receiveTransfer(ADMIN, shortTransferId, Map.of("items", List.of(Map.of(
        "itemId", shortItems.get(0).get("id").toString(), "quantity", 2))), "transfer-receive-short");
    assertBalance(MAIN_BRANCH, 75, 75, 0, 0, 0);
    assertBalance(destinationId, 11, 11, 0, 0, 0);

    service.applyCount(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "opening", false, "countReference", "AJUSTE-TESTE",
        "reason", "Ensaio de estorno", "items", List.of(Map.of("code", "INT-001", "quantity", 76))),
        "count-adjustment-1");
    Map<String, Object> movementWorkspace = service.workspace(ADMIN, MAIN_BRANCH.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> movements = (List<Map<String, Object>>) movementWorkspace.get("movements");
    UUID adjustmentId = id(movements.stream()
        .filter(item -> "AJUSTE_CONTAGEM".equals(item.get("tipo"))).findFirst().orElseThrow());
    service.reverseMovement(ADMIN, adjustmentId, "Validação de estorno compensatório", "reverse-adjustment-1");
    assertBalance(MAIN_BRANCH, 75, 75, 0, 0, 0);

    MmCheckServer.SessionPrincipal branchOperator =
        new MmCheckServer.SessionPrincipal("operator-1", "operador", "Operador", "receiving");
    service.assignBranch(ADMIN, Map.of("userId", branchOperator.id(), "branchId", destinationId.toString()),
        "assign-operator-1");
    assertEquals(destinationId.toString(), service.workspace(branchOperator, "").get("branchId"));
    try {
      service.workspace(branchOperator, MAIN_BRANCH.toString());
      throw new AssertionError("Operador não pode consultar outra filial.");
    } catch (EnterpriseDatabase.EnterpriseException expected) {
      assertEquals(403, expected.status());
    }

    assertTrue((Boolean) service.reconcile(ADMIN, MAIN_BRANCH.toString()).get("ok"));
    assertTrue((Boolean) service.reconcile(ADMIN, destinationId.toString()).get("ok"));

    Map<String, Object> workspace = service.workspace(ADMIN, MAIN_BRANCH.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> receipts = (List<Map<String, Object>>) workspace.get("receipts");
    Map<String, Object> summary = receipts.stream()
        .filter(item -> "CARGA-TESTE".equals(item.get("referencia"))).findFirst().orElseThrow();
    assertEquals(15, ((Number) summary.get("esperado")).intValue(),
        "Várias NF-e não podem multiplicar os totais do recebimento.");
    assertFalse(((List<?>) service.search(ADMIN, MAIN_BRANCH.toString(), "INT-001").get("results")).isEmpty());
  }

  private static Map<String, Object> createMap(String number, String hash, int quantity, String key) {
    return service.createMap(ADMIN, Map.of(
        "branchId", MAIN_BRANCH.toString(), "mapNumber", number, "orders", "PED-1",
        "fileName", number + ".pdf", "fileHash", hash,
        "items", List.of(Map.of("code", "INT-001", "quantity", quantity))), key);
  }

  private static Map<String, Object> createTransfer(UUID destinationId, String reference, int quantity, String key) {
    return service.createTransfer(ADMIN, Map.of(
        "originBranchId", MAIN_BRANCH.toString(), "destinationBranchId", destinationId.toString(),
        "reference", reference, "items", List.of(Map.of("code", "INT-001", "quantity", quantity))), key);
  }

  private static boolean publishAfter(CountDownLatch start, UUID mapId, String key) throws InterruptedException {
    start.await();
    try {
      service.publishMap(ADMIN, mapId, key);
      return true;
    } catch (EnterpriseDatabase.EnterpriseException expected) {
      assertEquals(409, expected.status());
      return false;
    }
  }

  private static Map<String, Object> nfe(String accessKey, int quantity) {
    String xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe><infNFe Id="NFe%s">
          <ide><dhEmi>2026-08-18T12:00:00Z</dhEmi></ide>
          <emit><CNPJ>12345678000199</CNPJ><xNome>Fornecedor Teste</xNome></emit>
          <dest><CNPJ>99887766000155</CNPJ><xNome>MN Teste</xNome></dest>
          <det nItem="1"><prod><cProd>SKU-001</cProd><cEAN>7891234567890</cEAN>
            <xProd>Produto de teste</xProd><qCom>%d.0000</qCom></prod></det>
        </infNFe></NFe></nfeProc>
        """.formatted(accessKey, quantity);
    return Map.of("fileName", accessKey + ".xml",
        "content", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static UUID id(Map<String, Object> row) {
    return (UUID) row.get("id");
  }

  @SuppressWarnings("unchecked")
  private static int total(Map<String, Object> receipt, String field) {
    return ((List<Map<String, Object>>) receipt.get("items")).stream()
        .mapToInt(item -> ((Number) item.get(field)).intValue()).sum();
  }

  @SuppressWarnings("unchecked")
  private static void assertBalance(UUID branch, int physical, int available, int reserved,
                                    int transit, int quarantine) {
    Map<String, Object> workspace = service.workspace(ADMIN, branch.toString());
    List<Map<String, Object>> balances = (List<Map<String, Object>>) workspace.get("balances");
    Map<String, Object> balance = balances.stream()
        .filter(item -> "SKU-001".equals(item.get("sku"))).findFirst().orElseThrow();
    assertEquals(physical, ((Number) balance.get("fisico")).intValue());
    assertEquals(available, ((Number) balance.get("disponivel")).intValue());
    assertEquals(reserved, ((Number) balance.get("reservado")).intValue());
    assertEquals(transit, ((Number) balance.get("em_transito")).intValue());
    assertEquals(quarantine, ((Number) balance.get("quarentena")).intValue());
  }
}
