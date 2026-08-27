package br.com.mncheck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class CountWorkbookExporter {
  static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final int MAX_TEMPLATE_BYTES = 15 * 1024 * 1024;
  private static final int FIRST_DATA_ROW = 1;

  private CountWorkbookExporter() {}

  static TemplateInfo validate(byte[] template) {
    if (template == null || template.length == 0) {
      throw new IllegalArgumentException("Selecione a planilha modelo em formato XLSX.");
    }
    if (template.length > MAX_TEMPLATE_BYTES) {
      throw new IllegalArgumentException("A planilha modelo deve ter no máximo 15 MB.");
    }
    try (XSSFWorkbook workbook = open(template)) {
      if (workbook.getNumberOfSheets() != 1) {
        throw new IllegalArgumentException("A planilha modelo deve possuir exatamente uma aba.");
      }
      XSSFSheet sheet = workbook.getSheetAt(0);
      Columns columns = resolveColumns(sheet);
      int products = indexRows(sheet, columns).size();
      if (products == 0) {
        throw new IllegalArgumentException(
            "A planilha não contém produtos com Código, Cor e Voltagem preenchidos.");
      }
      return new TemplateInfo(sheet.getSheetName(), products, template.length);
    } catch (OLE2NotOfficeXmlFileException error) {
      throw new IllegalArgumentException("O modelo deve ser um arquivo XLSX verdadeiro, não XLS antigo.", error);
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException("Não foi possível validar a planilha modelo: " + error.getMessage(), error);
    }
  }

  static byte[] export(byte[] template, List<MmCheckServer.CountItem> items) throws IOException {
    validate(template);
    try (XSSFWorkbook workbook = open(template); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = workbook.getSheetAt(0);
      Columns columns = resolveColumns(sheet);
      Map<String, Row> rowsBySku = indexRows(sheet, columns);
      Row styleSource = rowsBySku.values().stream().findFirst()
          .orElseThrow(() -> new IllegalArgumentException("A planilha modelo não possui uma linha de produto."));

      for (Row row : rowsBySku.values()) {
        setNumber(row, columns.counted(), 0, false);
        setNumber(row, columns.assistance(), 0, true);
        setNumber(row, columns.damaged(), 0, true);
        setNumber(row, columns.other(), 0, true);
        setNumber(row, columns.system(), 0, false);
        setFormulas(row, columns);
      }

      int nextRow = lastProductRow(sheet, columns) + 1;
      for (MmCheckServer.CountItem item : items) {
        Row row = rowsBySku.get(normalizeSku(item.sku()));
        if (row == null) {
          row = sheet.createRow(nextRow++);
          copyRowPresentation(styleSource, row, columns.lastColumn());
          clearNewRow(row, columns.lastColumn());
          writeSku(row, item.sku(), columns);
          rowsBySku.put(normalizeSku(item.sku()), row);
        }
        setTextPreservingStyle(row, columns.description(), item.description());
        setNumber(row, columns.counted(), item.counted(), false);
        setNumber(row, columns.assistance(), item.assistance(), true);
        setNumber(row, columns.damaged(), item.damaged(), true);
        setNumber(row, columns.other(), item.other(), true);
        setNumber(row, columns.system(), item.system(), false);
        setFormulas(row, columns);
      }

      if (sheet.getCTWorksheet().isSetAutoFilter()) {
        sheet.setAutoFilter(new CellRangeAddress(
            0, Math.max(lastProductRow(sheet, columns), FIRST_DATA_ROW), 0, columns.lastColumn()));
      }
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      workbook.setForceFormulaRecalculation(true);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static Columns resolveColumns(Sheet sheet) {
    Row header = sheet.getRow(0);
    if (header == null) throw new IllegalArgumentException("A planilha modelo não possui cabeçalho.");
    Map<Integer, String> headers = new LinkedHashMap<>();
    int lastColumn = Math.max(0, header.getLastCellNum() - 1);
    for (int column = 0; column <= lastColumn; column++) {
      String value = normalize(cellText(header.getCell(column)));
      if (!value.isBlank()) headers.put(column, value);
    }

    List<Integer> used = new ArrayList<>();
    int code = required(headers, used, "Código", "codigo", "cod produto", "cod");
    int color = required(headers, used, "Cor", "cor", "codigo cor", "cod cor");
    int voltage = required(headers, used, "Voltagem", "voltagem", "tensao", "volts");
    int description = required(headers, used, "Produto", "produto", "descricao", "nome produto", "item");
    int counted = required(headers, used, "Contagem", "contagem", "contado", "qtd contada", "quantidade contada");
    int assistance = optional(headers, used, "assistencia", "assist", "assist tecnica");
    int damaged = optional(headers, used, "avaria", "avariado", "danificado", "danificados");
    int other = optional(headers, used, "outros", "outro", "entrada nf", "ent nf", "entrada nota", "ajuste", "ajustes");
    int total = optional(headers, used, "c total", "contagem total", "total contado", "total contagem", "total");
    int system = required(headers, used, "Saldo", "saldo", "saldo sistema", "estoque sistema", "estoque");
    int difference = optional(headers, used, "diferenca", "divergencia", "saldo diferenca");
    int observation = optional(headers, used, "observacao", "obs", "motivo", "comentario");
    return new Columns(code, color, voltage, description, counted, assistance, damaged, other,
        total, system, difference, observation, lastColumn);
  }

  private static int required(Map<Integer, String> headers, List<Integer> used,
      String label, String... aliases) {
    int column = find(headers, used, aliases);
    if (column < 0) {
      throw new IllegalArgumentException("Não foi possível identificar a coluna “" + label
          + "”. Use um cabeçalho equivalente a: " + String.join(", ", aliases) + ".");
    }
    used.add(column);
    return column;
  }

  private static int optional(Map<Integer, String> headers, List<Integer> used, String... aliases) {
    int column = find(headers, used, aliases);
    if (column >= 0) used.add(column);
    return column;
  }

  private static int find(Map<Integer, String> headers, List<Integer> used, String... aliases) {
    int bestColumn = -1;
    int bestScore = -1;
    for (Map.Entry<Integer, String> header : headers.entrySet()) {
      if (used.contains(header.getKey())) continue;
      for (String rawAlias : aliases) {
        String alias = normalize(rawAlias);
        int score = header.getValue().equals(alias) ? 1000 + alias.length()
            : header.getValue().startsWith(alias + " ") || alias.startsWith(header.getValue() + " ")
                ? 500 + Math.min(alias.length(), header.getValue().length()) : -1;
        if (score > bestScore) {
          bestScore = score;
          bestColumn = header.getKey();
        }
      }
    }
    return bestColumn;
  }

  private static XSSFWorkbook open(byte[] bytes) throws IOException {
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static Map<String, Row> indexRows(Sheet sheet, Columns columns) {
    Map<String, Row> rows = new LinkedHashMap<>();
    for (int rowIndex = FIRST_DATA_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      String sku = skuOf(row, columns);
      if (!sku.isBlank()) rows.putIfAbsent(sku, row);
    }
    return rows;
  }

  private static int lastProductRow(Sheet sheet, Columns columns) {
    int last = FIRST_DATA_ROW;
    for (int index = FIRST_DATA_ROW; index <= sheet.getLastRowNum(); index++) {
      if (!skuOf(sheet.getRow(index), columns).isBlank()) last = index;
    }
    return last;
  }

  private static String skuOf(Row row, Columns columns) {
    if (row == null) return "";
    String code = identifier(row.getCell(columns.code()));
    String color = identifier(row.getCell(columns.color()));
    String voltage = identifier(row.getCell(columns.voltage()));
    if (code.isBlank() || color.isBlank() || voltage.isBlank()) return "";
    return normalizeSku(code + "." + color + "." + voltage);
  }

  private static void writeSku(Row row, String sku, Columns columns) {
    String[] parts = sku.split("\\.", -1);
    writeIdentifier(row, columns.code(), parts, 0);
    writeIdentifier(row, columns.color(), parts, 1);
    writeIdentifier(row, columns.voltage(), parts, 2);
  }

  private static void writeIdentifier(Row row, int column, String[] parts, int part) {
    Cell cell = cell(row, column);
    String value = part < parts.length ? parts[part] : "";
    try {
      cell.setCellValue(Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      cell.setCellValue(value);
    }
  }

  private static void clearNewRow(Row row, int lastColumn) {
    for (int column = 0; column <= lastColumn; column++) cell(row, column).setBlank();
  }

  private static void setFormulas(Row row, Columns columns) {
    int excelRow = row.getRowNum() + 1;
    if (columns.total() >= 0) {
      int firstQuantity = columns.description() + 1;
      int lastQuantity = columns.total() - 1;
      if (firstQuantity <= lastQuantity) {
        cell(row, columns.total()).setCellFormula(
            "SUM(" + columnName(firstQuantity) + excelRow + ":" + columnName(lastQuantity) + excelRow + ")");
      }
    }
    if (columns.difference() >= 0 && columns.total() >= 0) {
      cell(row, columns.difference()).setCellFormula(
          columnName(columns.total()) + excelRow + "-" + columnName(columns.system()) + excelRow);
    }
  }

  private static void setNumber(Row row, int column, int value, boolean blankWhenZero) {
    if (column < 0) return;
    Cell cell = cell(row, column);
    if (blankWhenZero && value == 0) cell.setBlank();
    else cell.setCellValue(value);
  }

  private static void setTextPreservingStyle(Row row, int column, String value) {
    if (column >= 0) cell(row, column).setCellValue(value == null ? "" : value);
  }

  private static Cell cell(Row row, int column) {
    Cell cell = row.getCell(column);
    return cell == null ? row.createCell(column) : cell;
  }

  private static void copyRowPresentation(Row source, Row target, int lastColumn) {
    target.setHeight(source.getHeight());
    for (int column = 0; column <= lastColumn; column++) {
      Cell sourceCell = source.getCell(column);
      Cell targetCell = target.createCell(column);
      if (sourceCell != null) {
        CellStyle style = sourceCell.getCellStyle();
        targetCell.setCellStyle(style);
      }
    }
  }

  private static String identifier(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) return "";
    if (cell.getCellType() == CellType.NUMERIC) {
      double number = cell.getNumericCellValue();
      long integer = (long) number;
      return number == integer ? Long.toString(integer) : Double.toString(number);
    }
    return cellText(cell).trim().replaceAll("\\.0$", "");
  }

  private static String cellText(Cell cell) {
    if (cell == null) return "";
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> Double.toString(cell.getNumericCellValue());
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case FORMULA -> cell.getCellFormula();
      default -> "";
    };
  }

  private static String normalizeSku(String value) {
    String[] parts = value.trim().split("\\.", -1);
    if (parts.length != 3) return value.trim().toLowerCase(Locale.ROOT);
    return stripZeros(parts[0]) + "." + stripZeros(parts[1]) + "." + stripZeros(parts[2]);
  }

  private static String stripZeros(String value) {
    try {
      return Long.toString(Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      return value.trim().toLowerCase(Locale.ROOT);
    }
  }

  private static String normalize(String value) {
    return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("[^a-zA-Z0-9]+", " ")
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  private static String columnName(int column) {
    StringBuilder name = new StringBuilder();
    int value = column + 1;
    while (value > 0) {
      value--;
      name.insert(0, (char) ('A' + value % 26));
      value /= 26;
    }
    return name.toString();
  }

  private record Columns(int code, int color, int voltage, int description, int counted,
      int assistance, int damaged, int other, int total, int system, int difference,
      int observation, int lastColumn) {}

  record TemplateInfo(String sheetName, int products, int bytes) {}
}
