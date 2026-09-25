package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class InventoryReportServiceTest {
  private static InventoryReportService.ReportItem item(String sku, String description, Integer quantity,
      String state, Integer balance, Integer difference, String investigation, String location, String condition) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (location != null) details.put(location, Map.of(condition, quantity == null ? 0 : quantity));
    return new InventoryReportService.ReportItem(sku, description, details, quantity, state,
        balance, difference, investigation, investigation == null ? null : "Conclusão");
  }

  private static InventoryReportService.ReportFilter filter(String context, String status,
      String location, String condition, String investigation, String search, String order) {
    return InventoryReportService.normalize(new InventoryReportService.ReportFilter(
        context, status, location, condition, investigation, search, order));
  }

  @Test
  void combinesR1DivergentAndDepositoFilters() {
    List<InventoryReportService.ReportItem> result = InventoryReportService.applyFilters(List.of(
        item("10.1.1", "Geladeira", 8, "DIVERGENTE", 10, -2, "PENDENTE", "DEPOSITO", "BOA"),
        item("11.1.1", "Fogão", 7, "DIVERGENTE", 8, -1, null, "VENDAS", "BOA"),
        item("12.1.1", "TV", 5, "CONFORME", 5, 0, null, "DEPOSITO", "BOA")
    ), filter("R1", "DIVERGENTES", "DEPOSITO", "TODAS", "TODAS", "", "SKU"), false);
    assertEquals(List.of("10.1.1"), result.stream().map(InventoryReportService.ReportItem::sku).toList());
  }

  @Test
  void supportsUncountedAndSkuOrDescriptionSearch() {
    List<InventoryReportService.ReportItem> source = List.of(
        item("20.1.1", "Lavadora Branca", null, "NAO_CONTADO", 4, null, null, null, null),
        item("21.1.1", "Secadora", 4, "CONFORME", 4, 0, null, "GERAL", "BOA")
    );
    assertEquals(1, InventoryReportService.applyFilters(source,
        filter("R1", "NAO_CONTADOS", "TODAS", "TODAS", "TODAS", "", "SKU"), false).size());
    assertEquals("20.1.1", InventoryReportService.applyFilters(source,
        filter("RESULTADO_FINAL", "TODOS", "TODAS", "TODAS", "TODAS", "lavadora", "SKU"), false).getFirst().sku());
  }

  @Test
  void filtersConditionAndInvestigationAndOrdersDeterministically() {
    List<InventoryReportService.ReportItem> result = InventoryReportService.applyFilters(List.of(
        item("100.2.1", "B", 1, "DIVERGENCIA_CONFIRMADA", 2, -1, "PENDENTE", "DEPOSITO", "AVARIA"),
        item("20.2.1", "A", 4, "DIVERGENCIA_CONFIRMADA", 2, 2, "PENDENTE", "DEPOSITO", "AVARIA")
    ), filter("RESULTADO_FINAL", "DIVERGENTES", "DEPOSITO", "AVARIA", "PENDENTE", "", "SKU"), false);
    assertEquals(List.of("20.2.1", "100.2.1"), result.stream().map(InventoryReportService.ReportItem::sku).toList());
  }

  @Test
  void conditionFilterRequiresPositiveQuantityAcrossLocations() {
    Map<String, Object> onlyGood = Map.of("GERAL", Map.of("BOA", 5, "AVARIA", 0));
    Map<String, Object> onlyDamaged = Map.of("DEPOSITO", Map.of("BOA", 0, "AVARIA", 2));
    Map<String, Object> both = Map.of("VENDAS", Map.of("BOA", 3), "DEPOSITO", Map.of("AVARIA", 1));
    Map<String, Object> allZero = Map.of("GERAL", Map.of("BOA", 0, "AVARIA", 0));
    List<InventoryReportService.ReportItem> source = List.of(
        new InventoryReportService.ReportItem("A", "Somente boa", onlyGood, 5, "DIVERGENTE", 4, 1, null, null),
        new InventoryReportService.ReportItem("B", "Somente avaria", onlyDamaged, 2, "DIVERGENTE", 1, 1, null, null),
        new InventoryReportService.ReportItem("C", "Ambas", both, 4, "DIVERGENTE", 3, 1, null, null),
        new InventoryReportService.ReportItem("D", "Zeros", allZero, 0, "CONFORME", 0, 0, null, null));

    assertEquals(List.of("A", "C"), InventoryReportService.applyFilters(source,
        filter("R1", "TODOS", "TODAS", "BOA", "TODAS", "", "SKU"), false)
        .stream().map(InventoryReportService.ReportItem::sku).toList());
    assertEquals(List.of("B", "C"), InventoryReportService.applyFilters(source,
        filter("R1", "TODOS", "TODAS", "AVARIA", "TODAS", "", "SKU"), false)
        .stream().map(InventoryReportService.ReportItem::sku).toList());
    assertEquals("", source.get(3).condicao());
  }

  @Test
  void blindProjectionRemovesEveryProtectedFieldBeforeApiOrExport() {
    InventoryReportService.ReportItem source = item("1.1.1", "Produto", 7, "DIVERGENTE", 10, -3, "PENDENTE", "GERAL", "BOA");
    InventoryReportService.ReportItem protectedItem = InventoryReportService.applyFilters(List.of(source),
        filter("R1", "TODOS", "TODAS", "TODAS", "TODAS", "", "SKU"), true).getFirst();
    assertNull(protectedItem.saldo());
    assertNull(protectedItem.diferenca());
    assertNull(protectedItem.statusInvestigacao());
    assertNull(protectedItem.conclusao());
    assertEquals("CONTADO", protectedItem.estado());
  }

  @Test
  void xlsxUsesProjectionAndKeepsNumericZeroNegativeShortageSurplusAndUnicode() throws Exception {
    InventoryReportService service = new InventoryReportService("postgresql://unused:unused@localhost:5432/unused", null);
    var filters = filter("RESULTADO_FINAL", "TODOS", "TODAS", "TODAS", "TODAS", "", "SKU");
    var projection = new InventoryReportService.ReportProjection(7, "Contagem São João", "281", "NORMAL", "ENCERRADO",
        "RESULTADO_FINAL", false, java.time.Instant.parse("2026-09-25T12:00:00Z"), filters,
        new InventoryReportService.Summary(3, 3, 0, 1, 2, 10, 10, 0), List.of(
          item("1.1.1", "Zero explícito", 0, "DIVERGENCIA_CONFIRMADA", 2, -2, "RESOLVIDA", "GERAL", "AVARIA"),
          item("2.1.1", "Sobra çã", 7, "DIVERGENCIA_CONFIRMADA", 5, 2, null, "VENDAS", "BOA"),
          item("3.1.1", "Conforme", 3, "CONFORME", 3, 0, null, "DEPOSITO", "ASSISTENCIA")
        ));
    byte[] bytes = service.exportXlsx(projection);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var sheet = workbook.getSheetAt(0);
      assertEquals("MN-Check — Relatório de Inventário 3.0", sheet.getRow(0).getCell(0).getStringCellValue());
      assertEquals(0d, sheet.getRow(9).getCell(4).getNumericCellValue());
      assertEquals(-2d, sheet.getRow(9).getCell(7).getNumericCellValue());
      assertEquals("Sobra çã", sheet.getRow(10).getCell(1).getStringCellValue());
    }
  }

  @Test
  void blindXlsxHasNoProtectedNumericValues() throws Exception {
    InventoryReportService service = new InventoryReportService("postgresql://unused:unused@localhost:5432/unused", null);
    var filters = filter("R2", "TODOS", "TODAS", "TODAS", "TODAS", "", "SKU");
    var safe = item("1.1.1", "Produto", 2, "CONTADO", null, null, null, "GERAL", "BOA");
    var projection = new InventoryReportService.ReportProjection(1, "Cego", "281", "CEGO", "EM_RECONTAGEM", "R2", true,
        java.time.Instant.now(), filters, new InventoryReportService.Summary(1,1,0,0,0,null,2,null), List.of(safe));
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportXlsx(projection)))) {
      var row = workbook.getSheetAt(0).getRow(9);
      assertNull(row.getCell(6));
      assertNull(row.getCell(7));
    }
  }
}
