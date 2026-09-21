package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFPatternFormatting;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CountWorkbookExporterTest {
  @Test
  void bundlesTheDefaultCountTemplateUsedByAutomaticExports() throws Exception {
    byte[] template = MmCheckServer.bundledCountWorkbookTemplate();
    assertNotNull(template);
    CountWorkbookExporter.TemplateInfo info = CountWorkbookExporter.validate(template);
    assertEquals("082026", info.sheetName());
    assertEquals(262, info.products());
  }

  @Test
  void updatesOnlyMnCheckFieldsAndPreservesTemplatePresentation() throws Exception {
    byte[] template = templateFixture();
    byte[] output = CountWorkbookExporter.export(template, List.of(
        new MmCheckServer.CountItem("1191.3.1", "FERRO ATUALIZADO", 179, 170, 2, 1, -4),
        new MmCheckServer.CountItem("2552.1.2", "REFRIGERADOR NOVO", 16, 11, 0, 4, 0)
    ));

    try (XSSFWorkbook before = open(template); XSSFWorkbook after = open(output)) {
      XSSFSheet source = before.getSheetAt(0);
      XSSFSheet sheet = after.getSheetAt(0);
      assertEquals(source.getSheetName(), sheet.getSheetName());
      assertEquals(source.getColumnWidth(3), sheet.getColumnWidth(3));
      assertEquals(source.getPaneInformation().getHorizontalSplitPosition(),
          sheet.getPaneInformation().getHorizontalSplitPosition());
      assertEquals(source.getPrintSetup().getLandscape(), sheet.getPrintSetup().getLandscape());
      assertEquals(source.getSheetConditionalFormatting().getNumConditionalFormattings(),
          sheet.getSheetConditionalFormatting().getNumConditionalFormattings());
      assertEquals(source.getRow(1).getCell(4).getNumericCellValue(),
          sheet.getRow(1).getCell(4).getNumericCellValue(), "Movimentação manual deve permanecer");
      assertEquals(source.getRow(1).getCell(7).getStringCellValue(),
          sheet.getRow(1).getCell(7).getStringCellValue(), "Clientes deve permanecer");
      assertEquals(source.getRow(1).getCell(17).getStringCellValue(),
          sheet.getRow(1).getCell(17).getStringCellValue(), "Observação deve permanecer");
      assertEquals("FERRO ATUALIZADO", sheet.getRow(1).getCell(3).getStringCellValue());
      assertEquals(170, sheet.getRow(1).getCell(8).getNumericCellValue());
      assertEquals(2, sheet.getRow(1).getCell(10).getNumericCellValue());
      assertEquals(1, sheet.getRow(1).getCell(11).getNumericCellValue());
      assertEquals(-4, sheet.getRow(1).getCell(12).getNumericCellValue());
      assertEquals(179, sheet.getRow(1).getCell(15).getNumericCellValue());
      assertEquals("SUM(E2:N2)", sheet.getRow(1).getCell(14).getCellFormula());
      assertEquals("O2-P2", sheet.getRow(1).getCell(16).getCellFormula());
      assertEquals(source.getRow(1).getCell(8).getCellStyle().getIndex(),
          sheet.getRow(1).getCell(8).getCellStyle().getIndex());
      assertEquals(2552, sheet.getRow(2).getCell(0).getNumericCellValue());
      assertEquals(source.getRow(1).getCell(0).getCellStyle().getIndex(),
          sheet.getRow(2).getCell(0).getCellStyle().getIndex());
    }
  }

  @Test
  void validatesExactExpectedHeaders() throws Exception {
    CountWorkbookExporter.TemplateInfo info = CountWorkbookExporter.validate(templateFixture());
    assertEquals("082026", info.sheetName());
    assertEquals(1, info.products());
  }

  @Test
  void insertsNewProductsInCodeColorVoltageOrderAndCopiesFormatting() throws Exception {
    byte[] template = templateFixture();
    byte[] output = CountWorkbookExporter.export(template, List.of(
        new MmCheckServer.CountItem("9000.3.2", "PRODUTO FINAL", 8, 1, 0, 0, 0),
        new MmCheckServer.CountItem("1000.4.3", "PRODUTO INICIAL", 5, 2, 0, 0, 0),
        new MmCheckServer.CountItem("1191.2.3", "MESMO CODIGO", 6, 3, 0, 0, 0)
    ));

    try (XSSFWorkbook workbook = open(output)) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals(1000, sheet.getRow(1).getCell(0).getNumericCellValue());
      assertEquals(1191, sheet.getRow(2).getCell(0).getNumericCellValue());
      assertEquals(2, sheet.getRow(2).getCell(1).getNumericCellValue());
      assertEquals(1191, sheet.getRow(3).getCell(0).getNumericCellValue());
      assertEquals(3, sheet.getRow(3).getCell(1).getNumericCellValue());
      assertEquals(9000, sheet.getRow(4).getCell(0).getNumericCellValue());
      assertEquals(sheet.getRow(3).getCell(0).getCellStyle().getIndex(),
          sheet.getRow(4).getCell(0).getCellStyle().getIndex());
      assertEquals("SUM(E5:N5)", sheet.getRow(4).getCell(14).getCellFormula());
      assertEquals("O5-P5", sheet.getRow(4).getCell(16).getCellFormula());
    }
  }

  @Test
  void discoversAliasesAndColumnsRegardlessOfTheirOriginalPosition() throws Exception {
    byte[] template = adaptiveTemplateFixture();
    CountWorkbookExporter.TemplateInfo info = CountWorkbookExporter.validate(template);
    assertEquals(1, info.products());

    byte[] output = CountWorkbookExporter.export(template, List.of(
        new MmCheckServer.CountItem("1191.3.1", "FERRO ADAPTATIVO", 179, 170, 2, 1, -4)
    ));
    try (XSSFWorkbook workbook = open(output)) {
      Row row = workbook.getSheetAt(0).getRow(1);
      assertEquals("FERRO ADAPTATIVO", row.getCell(5).getStringCellValue());
      assertEquals(170, row.getCell(4).getNumericCellValue());
      assertEquals(2, row.getCell(7).getNumericCellValue());
      assertEquals(1, row.getCell(8).getNumericCellValue());
      assertEquals(-4, row.getCell(9).getNumericCellValue());
      assertEquals(179, row.getCell(11).getNumericCellValue());
      assertEquals("SUM(G2:J2)", row.getCell(10).getCellFormula());
      assertEquals("K2-L2", row.getCell(12).getCellFormula());
    }
  }

  @Test
  void comparesAgainstUserWorkbookWhenProvided() throws Exception {
    String path = System.getenv("MN_CHECK_XLSX_TEMPLATE");
    if (path == null || path.isBlank()) return;
    byte[] template = Files.readAllBytes(Path.of(path));
    List<MmCheckServer.CountItem> items = itemsFrom(template);
    byte[] output = CountWorkbookExporter.export(template, items);
    Files.write(Path.of("target", "contagem-validada-modelo-real.xlsx"), output);

    try (XSSFWorkbook before = open(template); XSSFWorkbook after = open(output)) {
      assertEquals(before.getNumberOfSheets(), after.getNumberOfSheets());
      assertEquals(before.getNumCellStyles(), after.getNumCellStyles());
      assertEquals(before.getNumberOfFonts(), after.getNumberOfFonts());
      assertEquals(before.getStylesSource().getFills().size(), after.getStylesSource().getFills().size());
      assertEquals(before.getStylesSource().getBorders().size(), after.getStylesSource().getBorders().size());
      XSSFSheet source = before.getSheetAt(0);
      XSSFSheet result = after.getSheetAt(0);
      assertEquals(source.getSheetName(), result.getSheetName());
      assertEquals(source.getNumMergedRegions(), result.getNumMergedRegions());
      assertEquals(source.getDrawingPatriarch().getShapes().size(), result.getDrawingPatriarch().getShapes().size());
      assertEquals(source.getSheetConditionalFormatting().getNumConditionalFormattings(),
          result.getSheetConditionalFormatting().getNumConditionalFormattings());
      assertEquals(source.getPrintSetup().getOrientation(), result.getPrintSetup().getOrientation());
      assertEquals(source.getPrintSetup().getPaperSize(), result.getPrintSetup().getPaperSize());
      assertEquals(source.getPaneInformation().getHorizontalSplitPosition(),
          result.getPaneInformation().getHorizontalSplitPosition());
      for (int column = 0; column <= 20; column++) {
        assertEquals(source.getColumnWidth(column), result.getColumnWidth(column), "Largura da coluna " + column);
      }
      for (int rowIndex = 0; rowIndex <= source.getLastRowNum(); rowIndex++) {
        Row sourceRow = source.getRow(rowIndex);
        Row resultRow = result.getRow(rowIndex);
        if (sourceRow == null || resultRow == null) continue;
        assertEquals(sourceRow.getHeight(), resultRow.getHeight(), "Altura da linha " + rowIndex);
        for (int column = 0; column <= 20; column++) {
          Cell a = sourceRow.getCell(column);
          Cell b = resultRow.getCell(column);
          if (a != null && b != null) {
            assertEquals(a.getCellStyle().getIndex(), b.getCellStyle().getIndex(),
                "Estilo em " + rowIndex + "," + column);
          }
        }
        for (int manualColumn : new int[]{4, 5, 6, 7, 9, 13, 17}) {
          assertEquals(display(sourceRow.getCell(manualColumn)), display(resultRow.getCell(manualColumn)),
              "Coluna manual em " + rowIndex + "," + manualColumn);
        }
      }
      compareConditionalFormatting(source, result);
    }
  }

  private static byte[] templateFixture() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = workbook.createSheet("082026");
      String[] headers = {"Código", "Cor", "Voltagem", "Produto", "Movimentação Agosto", "Trans.",
          "Licitação", "Clientes", "Contagem", "Aud.", "Assist.", "Avaria", "Ent NF.", "Falta",
          "C. Total", "Saldo", "Diferença", "OBS.", "", "Sumário", "Cor"};
      Row header = sheet.createRow(0);
      CellStyle headerStyle = workbook.createCellStyle();
      headerStyle.setAlignment(HorizontalAlignment.CENTER);
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
        header.getCell(i).setCellStyle(headerStyle);
        sheet.setColumnWidth(i, (10 + i) * 256);
      }
      Row row = sheet.createRow(1);
      CellStyle body = workbook.createCellStyle();
      body.setAlignment(HorizontalAlignment.CENTER);
      for (int i = 0; i <= 20; i++) row.createCell(i).setCellStyle(body);
      row.getCell(0).setCellValue(1191);
      row.getCell(1).setCellValue(3);
      row.getCell(2).setCellValue(1);
      row.getCell(3).setCellValue("FERRO ORIGINAL");
      row.getCell(4).setCellValue(25);
      row.getCell(7).setCellValue("Cliente X");
      row.getCell(8).setCellValue(10);
      row.getCell(14).setCellFormula("SUM(E2:N2)");
      row.getCell(15).setCellValue(100);
      row.getCell(16).setCellFormula("O2-P2");
      row.getCell(17).setCellValue("Manter esta observação");
      sheet.createFreezePane(0, 1);
      sheet.getPrintSetup().setLandscape(true);
      sheet.setAutoFilter(new CellRangeAddress(0, 1, 0, 17));
      addRule(sheet, "$R2=\"Inversão\"", "46BDC6");
      addRule(sheet, "$R2=\"Outro\"", "4285F4");
      addRule(sheet, "$Q2>0", "FBBC04");
      addRule(sheet, "$Q2<0", "EA4335");
      addRule(sheet, "$Q2=0", "34A853");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static byte[] adaptiveTemplateFixture() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = workbook.createSheet("Modelo livre");
      String[] headers = {"Cor", "Código", "Tensão", "Observações", "Qtd. contada", "Descrição",
          "Movimentação", "Assistência técnica", "Danificados", "Outros", "Total contado",
          "Estoque sistema", "Divergência"};
      Row header = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
      Row row = sheet.createRow(1);
      for (int i = 0; i < headers.length; i++) row.createCell(i);
      row.getCell(0).setCellValue(3);
      row.getCell(1).setCellValue(1191);
      row.getCell(2).setCellValue(1);
      row.getCell(5).setCellValue("FERRO ORIGINAL");
      row.getCell(6).setCellValue(25);
      row.getCell(10).setCellFormula("SUM(G2:J2)");
      row.getCell(11).setCellValue(100);
      row.getCell(12).setCellFormula("K2-L2");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static void addRule(XSSFSheet sheet, String formula, String hex) {
    var formatting = sheet.getSheetConditionalFormatting();
    XSSFConditionalFormattingRule rule = formatting.createConditionalFormattingRule(formula);
    XSSFPatternFormatting pattern = rule.createPatternFormatting();
    pattern.setFillPattern(FillPatternType.SOLID_FOREGROUND.getCode());
    pattern.setFillForegroundColor(new XSSFColor(new byte[]{
        (byte) Integer.parseInt(hex.substring(0, 2), 16),
        (byte) Integer.parseInt(hex.substring(2, 4), 16),
        (byte) Integer.parseInt(hex.substring(4, 6), 16)
    }, null));
    formatting.addConditionalFormatting(new CellRangeAddress[]{new CellRangeAddress(1, 1, 0, 17)}, rule);
  }

  private static List<MmCheckServer.CountItem> itemsFrom(byte[] template) throws Exception {
    List<MmCheckServer.CountItem> items = new ArrayList<>();
    try (XSSFWorkbook workbook = open(template)) {
      Sheet sheet = workbook.getSheetAt(0);
      for (int i = 1; i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row == null || display(row.getCell(0)).isBlank() || display(row.getCell(1)).isBlank()
            || display(row.getCell(2)).isBlank()) continue;
        String sku = id(row.getCell(0)) + "." + id(row.getCell(1)) + "." + id(row.getCell(2));
        items.add(new MmCheckServer.CountItem(sku, display(row.getCell(3)), integer(row.getCell(15)),
            integer(row.getCell(8)), integer(row.getCell(10)), integer(row.getCell(11)), integer(row.getCell(12))));
      }
    }
    return items;
  }

  private static void compareConditionalFormatting(XSSFSheet source, XSSFSheet result) {
    var a = source.getSheetConditionalFormatting();
    var b = result.getSheetConditionalFormatting();
    for (int i = 0; i < a.getNumConditionalFormattings(); i++) {
      assertArrayEquals(a.getConditionalFormattingAt(i).getFormattingRanges(),
          b.getConditionalFormattingAt(i).getFormattingRanges(), "Intervalo condicional " + i);
      assertEquals(a.getConditionalFormattingAt(i).getNumberOfRules(),
          b.getConditionalFormattingAt(i).getNumberOfRules());
      for (int rule = 0; rule < a.getConditionalFormattingAt(i).getNumberOfRules(); rule++) {
        var sourceRule = a.getConditionalFormattingAt(i).getRule(rule);
        var resultRule = b.getConditionalFormattingAt(i).getRule(rule);
        assertEquals(sourceRule.getConditionType(), resultRule.getConditionType());
        assertEquals(sourceRule.getPriority(), resultRule.getPriority());
        assertEquals(sourceRule.getStopIfTrue(), resultRule.getStopIfTrue());
        assertEquals(sourceRule.getFormula1(), resultRule.getFormula1());
        if (sourceRule.getPatternFormatting() != null) {
          assertEquals(sourceRule.getPatternFormatting().getFillForegroundColorColor().getARGBHex(),
              resultRule.getPatternFormatting().getFillForegroundColorColor().getARGBHex());
        }
      }
    }
  }

  private static XSSFWorkbook open(byte[] bytes) throws Exception {
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static int integer(Cell cell) {
    if (cell == null) return 0;
    return cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
        ? (int) cell.getNumericCellValue() : 0;
  }

  private static String id(Cell cell) {
    if (cell == null) return "";
    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
      return Long.toString((long) cell.getNumericCellValue());
    }
    return display(cell).replaceAll("\\.0$", "");
  }

  private static String display(Cell cell) {
    if (cell == null) return "";
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> Double.toString(cell.getNumericCellValue());
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case FORMULA -> cell.getCellFormula();
      default -> "";
    };
  }
}
