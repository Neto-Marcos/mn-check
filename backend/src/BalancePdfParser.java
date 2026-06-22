package br.com.mncheck;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BalancePdfParser {
  private static final float Y_TOLERANCE = 1.8f;

  private BalancePdfParser() {}

  static Result parse(byte[] pdfBytes) throws IOException {
    Instant startedAt = Instant.now();
    PositionedTextStripper stripper = new PositionedTextStripper();
    List<PageGlyphs> pages;
    try (PDDocument document = Loader.loadPDF(pdfBytes)) {
      if (document.isEncrypted()) {
        throw new IOException("O PDF de saldo está protegido por senha.");
      }
      stripper.getText(document);
      pages = stripper.pages;
    }

    Map<String, Row> unique = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();
    List<IgnoredLine> ignored = new ArrayList<>();
    List<String> processingLog = new ArrayList<>();
    StringBuilder rawText = new StringBuilder();
    int totalLinesRead = 0;
    int ignoredLines = 0;
    int duplicateSkus = 0;

    for (PageGlyphs page : pages) {
      List<TextLine> lines = groupLines(page.glyphs);
      rawText.append("--- FOLHA ").append(page.number).append(" ---\n");
      for (TextLine line : lines) {
        if (!line.text().isBlank()) rawText.append(line.text()).append('\n');
      }
      rawText.append('\n');

      ColumnLayout layout = findLayout(lines);
      if (layout == null) {
        for (TextLine line : lines) {
          String text = line.text().trim();
          if (text.isBlank()) continue;
          totalLinesRead++;
          ignoredLines++;
          addIgnored(ignored, processingLog, page.number, text, "", "Cabeçalho da tabela não localizado.");
        }
        warnings.add("Folha " + page.number + " ignorada: cabeçalho da tabela não foi localizado.");
        continue;
      }
      if ("true".equalsIgnoreCase(System.getenv("MMCHECK_PDF_DEBUG"))) {
        System.err.println("SALDO_PDF_LAYOUT folha=" + page.number + " " + layout);
      }

      for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
        TextLine line = lines.get(lineIndex);
        String text = line.text().trim();
        if (text.isBlank()) continue;
        totalLinesRead++;
        String debugSku = System.getenv().getOrDefault("MMCHECK_PDF_DEBUG_SKU", "73578");
        if ("true".equalsIgnoreCase(System.getenv("MMCHECK_PDF_DEBUG")) && text.contains(debugSku)) {
          System.err.println("SALDO_PDF_POSICOES " + line.positions());
        }
        if (line == layout.headerLine) {
          ignoredLines++;
          addIgnored(ignored, processingLog, page.number, text, "", "Cabeçalho da tabela.");
          continue;
        }
        if (isKnownNonDataLine(text)) {
          ignoredLines++;
          addIgnored(ignored, processingLog, page.number, text, "", nonDataReason(text));
          continue;
        }

        ParsedColumns columns = layout.read(line);
        BalanceResolution balanceResolution = resolveBalance(line, layout, columns);
        if ((!columns.looksLikeDataRow() || balanceResolution.value() == null)
            && lineIndex + 1 < lines.size()) {
          TextLine nextLine = lines.get(lineIndex + 1);
          String nextText = nextLine.text().trim();
          if (!nextText.isBlank() && !isKnownNonDataLine(nextText) && nextLine != layout.headerLine) {
            TextLine merged = line.merge(nextLine);
            ParsedColumns mergedColumns = layout.read(merged);
            BalanceResolution mergedBalance = resolveBalance(merged, layout, mergedColumns);
            if (mergedColumns.looksLikeDataRow() && mergedBalance.value() != null) {
              columns = mergedColumns;
              balanceResolution = mergedBalance;
              text = text + " | " + nextText;
              totalLinesRead++;
              lineIndex++;
              processingLog.add("[RECONSTRUÍDA] folha=" + page.number + " linha=\"" + text + "\"");
            }
          }
        }

        String product = onlyDigits(columns.productCode);
        String gradeX = onlyDigits(columns.gradeX);
        String gradeY = onlyDigits(columns.gradeY);
        Integer balance = balanceResolution.value();
        String rejectionReason = validationError(product, gradeX, gradeY, balance, columns);
        if (rejectionReason != null) {
          debugIgnored(page.number, text, columns);
          ignoredLines++;
          addIgnored(ignored, processingLog, page.number, text, product, rejectionReason);
          continue;
        }

        String sku = product + "." + gradeX + "." + gradeY;
        Row row = new Row(sku, balance, page.number, text);
        Row previous = unique.get(sku);
        if (previous == null) {
          unique.put(sku, row);
          processingLog.add("[LIDA] folha=" + page.number + " produto=" + sku
              + " saldo=" + balance + " linha=\"" + text + "\"");
        } else {
          duplicateSkus++;
          int consolidatedBalance = previous.balance + balance;
          unique.put(sku, new Row(
              sku,
              consolidatedBalance,
              previous.page,
              previous.sourceLine + " | " + text
          ));
          processingLog.add("[DUPLICADA] folha=" + page.number + " produto=" + sku
              + " saldo_anterior=" + previous.balance + " saldo_adicionado=" + balance
              + " saldo_final=" + consolidatedBalance);
          warnings.add("SKU duplicado somado: " + sku + " (" + previous.balance
              + " + " + balance + " = " + consolidatedBalance + ").");
        }
      }
    }

    long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
    Metrics metrics = new Metrics(
        pages.size(),
        totalLinesRead,
        unique.size(),
        ignoredLines,
        duplicateSkus,
        conflicts.size(),
        elapsedMs
    );
    return new Result(
        new ArrayList<>(unique.values()),
        warnings.stream().distinct().toList(),
        conflicts,
        metrics,
        ignored,
        buildDebugReport(rawText, processingLog, unique, metrics)
    );
  }

  private static List<TextLine> groupLines(List<Glyph> glyphs) {
    List<Glyph> byPosition = new ArrayList<>(glyphs);
    byPosition.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
    List<List<Glyph>> grouped = new ArrayList<>();
    for (Glyph glyph : byPosition) {
      List<Glyph> closest = null;
      float closestDistance = Float.MAX_VALUE;
      for (int index = grouped.size() - 1; index >= 0; index--) {
        List<Glyph> candidate = grouped.get(index);
        float distance = Math.abs(candidate.get(0).y - glyph.y);
        if (distance <= Y_TOLERANCE && distance < closestDistance) {
          closest = candidate;
          closestDistance = distance;
        }
        if (candidate.get(0).y < glyph.y - Y_TOLERANCE) break;
      }
      if (closest == null) {
        closest = new ArrayList<>();
        grouped.add(closest);
      }
      closest.add(glyph);
    }

    return grouped.stream()
        .map(line -> {
          line.sort(Comparator.comparingDouble(Glyph::x));
          return new TextLine(line);
        })
        .sorted(Comparator.comparingDouble(TextLine::y))
        .toList();
  }

  private static ColumnLayout findLayout(List<TextLine> lines) {
    for (TextLine line : lines) {
      List<Word> words = line.words();
      List<Word> cod = wordsNamed(words, "cod");
      List<Word> grades = wordsNamed(words, "grade");
      List<Word> products = wordsNamed(words, "produto");
      Word balance = firstWord(words, "saldo");
      Word cost = firstWord(words, "custo");
      Word total = firstWord(words, "total");
      if (cod.size() >= 2 && grades.size() >= 2 && products.size() >= 2
          && balance != null && cost != null && total != null) {
        return new ColumnLayout(
            line,
            cod.get(0).x,
            cod.get(1).x,
            grades.get(0).x,
            grades.get(1).x,
            products.get(products.size() - 1).x,
            balance.x,
            cost.x,
            total.x
        );
      }
    }
    return null;
  }

  private static List<Word> wordsNamed(List<Word> words, String expected) {
    return words.stream().filter(word -> normalize(word.text).equals(expected)).toList();
  }

  private static Word firstWord(List<Word> words, String expected) {
    return words.stream().filter(word -> normalize(word.text).equals(expected)).findFirst().orElse(null);
  }

  private static boolean isKnownNonDataLine(String text) {
    String normalized = normalize(text);
    return normalized.startsWith("folha")
        || normalized.contains("saldo produto filial")
        || normalized.contains("mercadomoveis")
        || normalized.startsWith("total geral")
        || normalized.contains("desenvolvimento - ti")
        || normalized.startsWith("1 - filial")
        || normalized.matches("\\d{2}/\\d{2}/\\d{4}.*");
  }

  private static String nonDataReason(String text) {
    String normalized = normalize(text);
    if (normalized.startsWith("total")) return "Total ou rodapé.";
    if (normalized.contains("produto") || normalized.contains("grade") || normalized.contains("saldo")) {
      return "Cabeçalho ou filtro do relatório.";
    }
    return "Linha informativa do relatório.";
  }

  private static void addIgnored(
      List<IgnoredLine> ignored,
      List<String> processingLog,
      int page,
      String line,
      String product,
      String reason
  ) {
    IgnoredLine ignoredLine = new IgnoredLine(page, line, product, reason);
    ignored.add(ignoredLine);
    processingLog.add("[IGNORADA] folha=" + page
        + " produto=\"" + product + "\" motivo=\"" + reason + "\" linha=\"" + line + "\"");
  }

  private static String validationError(
      String product,
      String gradeX,
      String gradeY,
      Integer balance,
      ParsedColumns columns
  ) {
    if (!columns.looksLikeDataRow()) return "Linha sem todas as colunas de Produto, Grade X e Grade Y.";
    if (!isValidProduct(product, 10)) return "Código de produto ausente ou inválido.";
    if (product.startsWith("9999999") || isBlockedProductCode(product)) {
      return "Código " + product + " bloqueado como cabeçalho/lixo.";
    }
    if (!isValidGrade(gradeX, 10)) return "Grade X ausente ou inválida.";
    if (!isValidVoltageGrade(gradeY)) return "Grade Y ausente ou fora do intervalo aceito (0 a 4).";
    if (balance == null) return "Saldo numérico não pôde ser identificado.";
    if (balance < 0) return "Saldo negativo não permitido.";
    return null;
  }

  private static String buildDebugReport(
      StringBuilder rawText,
      List<String> processingLog,
      Map<String, Row> rows,
      Metrics metrics
  ) {
    StringBuilder report = new StringBuilder();
    report.append("MN - Check | Debug da importação de saldo\n");
    report.append("Páginas processadas: ").append(metrics.pagesProcessed()).append('\n');
    report.append("Linhas lidas: ").append(metrics.totalLinesRead()).append('\n');
    report.append("Produtos importados: ").append(metrics.skusRead()).append('\n');
    report.append("Linhas ignoradas: ").append(metrics.ignoredLines()).append('\n');
    report.append("Duplicados somados: ").append(metrics.duplicateSkus()).append("\n\n");
    report.append("=== TEXTO BRUTO EXTRAÍDO ===\n");
    report.append(rawText);
    report.append("=== PROCESSAMENTO ===\n");
    processingLog.forEach(entry -> report.append(entry).append('\n'));
    report.append("\n=== RESULTADO FINAL ===\n");
    rows.values().forEach(row -> report.append(row.sku)
        .append(" = ").append(row.balance)
        .append(" | folha ").append(row.page)
        .append('\n'));
    return report.toString();
  }

  private static String onlyDigits(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  private static boolean isValidProduct(String value, int maxLength) {
    return !value.isBlank()
        && value.length() <= maxLength
        && !"0".equals(value);
  }

  private static boolean isBlockedProductCode(String value) {
    String digits = onlyDigits(value);
    return digits.length() >= 7 && digits.chars().allMatch(character -> character == '9');
  }

  private static boolean isValidGrade(String value, int maxLength) {
    return !value.isBlank() && value.length() <= maxLength;
  }

  private static boolean isValidVoltageGrade(String value) {
    return value != null && value.matches("[0-4]");
  }

  private static Integer parseInteger(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    if (!normalized.matches("\\d+|\\d{1,3}(?:\\.\\d{3})+")) return null;
    normalized = normalized.replace(".", "");
    try {
      return Integer.parseInt(normalized);
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static BalanceResolution resolveBalance(
      TextLine line,
      ColumnLayout layout,
      ParsedColumns columns
  ) {
    Integer positioned = parseInteger(line.integerClusterClosestTo(
        layout.balance,
        midpoint(layout.balance, layout.cost),
        layout.balance + Math.min(24f, (layout.cost - layout.balance) * 0.35f)
    ));
    Integer direct = parseInteger(columns.balance);
    BigDecimal cost = parseDecimal(columns.cost);
    BigDecimal total = parseDecimal(columns.total);
    Integer calculated = calculateBalance(cost, total);
    if (calculated != null && (positioned == null || !calculated.equals(positioned))) {
      return new BalanceResolution(calculated, "posição validada por Total ÷ Custo Médio");
    }
    if (positioned != null) {
      return new BalanceResolution(positioned, "posição da coluna Saldo");
    }
    if (direct != null) {
      return new BalanceResolution(direct, "texto da coluna Saldo");
    }
    if (calculated != null) {
      return new BalanceResolution(calculated, "Total ÷ Custo Médio (fallback)");
    }
    return new BalanceResolution(null, "saldo não identificado");
  }

  private static float midpoint(float left, float right) {
    return left + (right - left) / 2f;
  }

  private static BigDecimal parseDecimal(String value) {
    if (value == null) return null;
    String normalized = value.replace(" ", "").replace(".", "").replace(",", ".");
    if (!normalized.matches("-?\\d+(\\.\\d+)?")) return null;
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static Integer calculateBalance(BigDecimal cost, BigDecimal total) {
    if (cost == null || total == null || cost.compareTo(BigDecimal.ZERO) == 0) return null;
    try {
      BigDecimal rounded = total.divide(cost, 0, RoundingMode.HALF_UP);
      BigDecimal difference = cost.multiply(rounded).subtract(total).abs();
      if (difference.compareTo(new BigDecimal("0.02")) > 0) return null;
      return rounded.intValueExact();
    } catch (ArithmeticException error) {
      return null;
    }
  }

  private static void debugIgnored(int page, String line, ParsedColumns columns) {
    if (!"true".equalsIgnoreCase(System.getenv("MMCHECK_PDF_DEBUG"))) return;
    if (!line.matches("^\\d+.*")) return;
    System.err.println("SALDO_PDF_IGNORADA folha=" + page
        + " filial=\"" + columns.branch + "\""
        + " produto=\"" + columns.productCode + "\""
        + " grade_x=\"" + columns.gradeX + "\""
        + " grade_y=\"" + columns.gradeY + "\""
        + " saldo=\"" + columns.balance + "\""
        + " custo=\"" + columns.cost + "\""
        + " total=\"" + columns.total + "\""
        + " linha=\"" + line + "\"");
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("[^A-Za-z0-9]+", " ")
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  record Row(String sku, int balance, int page, String sourceLine) {}

  record Metrics(
      int pagesProcessed,
      int totalLinesRead,
      int skusRead,
      int ignoredLines,
      int duplicateSkus,
      int conflictsFound,
      long elapsedMs
  ) {
    Map<String, Object> toMap() {
      return Map.of(
          "pagesProcessed", pagesProcessed,
          "totalLinesRead", totalLinesRead,
          "skusRead", skusRead,
          "ignoredLines", ignoredLines,
          "duplicateSkus", duplicateSkus,
          "conflictsFound", conflictsFound,
          "elapsedMs", elapsedMs
      );
    }

    static Metrics empty() {
      return new Metrics(0, 0, 0, 0, 0, 0, 0);
    }

    static Metrics fromMap(Map<String, Object> map) {
      return new Metrics(
          intValue(map.get("pagesProcessed")),
          intValue(map.get("totalLinesRead")),
          intValue(map.get("skusRead")),
          intValue(map.get("ignoredLines")),
          intValue(map.get("duplicateSkus")),
          intValue(map.get("conflictsFound")),
          longValue(map.get("elapsedMs"))
      );
    }
  }

  record IgnoredLine(int page, String line, String product, String reason) {
    Map<String, Object> toMap() {
      return Map.of(
          "page", page,
          "line", line,
          "product", product,
          "reason", reason
      );
    }
  }

  record Result(
      List<Row> rows,
      List<String> warnings,
      List<String> conflicts,
      Metrics metrics,
      List<IgnoredLine> ignored,
      String debugReport
  ) {}

  private record BalanceResolution(Integer value, String source) {}

  private record Glyph(String text, float x, float y, float width, float fontSize, int chunk) {}

  private record Word(String text, float x) {}

  private record PageGlyphs(int number, List<Glyph> glyphs) {}

  private static final class TextLine {
    private final List<Glyph> glyphs;

    private TextLine(List<Glyph> glyphs) {
      this.glyphs = glyphs;
    }

    float y() {
      return glyphs.isEmpty() ? 0 : glyphs.get(0).y;
    }

    String text() {
      StringBuilder result = new StringBuilder();
      Glyph previous = null;
      for (Glyph glyph : glyphs) {
        if (previous != null && shouldAddSpace(previous, glyph)) result.append(' ');
        result.append(glyph.text);
        previous = glyph;
      }
      return result.toString().replaceAll("\\s+", " ").trim();
    }

    List<Word> words() {
      List<Word> result = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      float startX = 0;
      Glyph previous = null;
      for (Glyph glyph : glyphs) {
        boolean separator = glyph.text.isBlank() || (previous != null && shouldAddSpace(previous, glyph));
        if (separator && !current.isEmpty()) {
          result.add(new Word(current.toString(), startX));
          current.setLength(0);
        }
        if (!glyph.text.isBlank()) {
          if (current.isEmpty()) startX = glyph.x;
          current.append(glyph.text);
        }
        previous = glyph;
      }
      if (!current.isEmpty()) result.add(new Word(current.toString(), startX));
      return result;
    }

    String between(float startX, float endX) {
      StringBuilder result = new StringBuilder();
      Glyph previous = null;
      for (Glyph glyph : glyphs) {
        float center = glyph.x + glyph.width / 2f;
        if (center < startX || center >= endX) continue;
        if (previous != null && shouldAddSpace(previous, glyph)) result.append(' ');
        result.append(glyph.text);
        previous = glyph;
      }
      return result.toString().replaceAll("\\s+", " ").trim();
    }

    String positions() {
      return glyphs.stream()
          .map(glyph -> glyph.text + "@" + Math.round(glyph.x) + "#" + glyph.chunk)
          .reduce((left, right) -> left + " " + right)
          .orElse("");
    }

    TextLine merge(TextLine other) {
      List<Glyph> merged = new ArrayList<>(glyphs);
      merged.addAll(other.glyphs);
      merged.sort(Comparator.comparingDouble(Glyph::x));
      return new TextLine(merged);
    }

    String integerClusterClosestTo(float startX, float endX, float preferredEndX) {
      List<Glyph> digits = glyphs.stream()
          .filter(glyph -> glyph.text.matches("\\d"))
          .filter(glyph -> {
            float center = glyph.x + glyph.width / 2f;
            return center >= startX && center < endX;
          })
          .sorted(Comparator.comparingDouble(Glyph::x))
          .toList();
      if (digits.isEmpty()) return "";

      List<List<Glyph>> clusters = new ArrayList<>();
      for (Glyph glyph : digits) {
        if (clusters.isEmpty()) {
          clusters.add(new ArrayList<>(List.of(glyph)));
          continue;
        }
        List<Glyph> current = clusters.get(clusters.size() - 1);
        Glyph previous = current.get(current.size() - 1);
        if (glyph.x - previous.x <= 8.5f) {
          if (Math.abs(glyph.x - previous.x) > 0.3f || !glyph.text.equals(previous.text)) {
            current.add(glyph);
          }
        } else {
          clusters.add(new ArrayList<>(List.of(glyph)));
        }
      }
      List<Glyph> selected = clusters.stream()
          .min(Comparator.comparingDouble(cluster ->
              Math.abs(cluster.get(cluster.size() - 1).x - preferredEndX)))
          .orElse(clusters.get(clusters.size() - 1));
      return selected.stream()
          .map(Glyph::text)
          .reduce("", String::concat);
    }

    List<String> chunkTexts() {
      Map<Integer, List<Glyph>> chunks = new LinkedHashMap<>();
      for (Glyph glyph : glyphs) {
        chunks.computeIfAbsent(glyph.chunk, ignored -> new ArrayList<>()).add(glyph);
      }
      return chunks.values().stream()
          .map(chunkGlyphs -> {
            chunkGlyphs.sort(Comparator.comparingDouble(Glyph::x));
            return new TextLine(chunkGlyphs).text();
          })
          .filter(value -> !value.isBlank())
          .toList();
    }

    private static boolean shouldAddSpace(Glyph previous, Glyph current) {
      float gap = current.x - (previous.x + previous.width);
      float threshold = Math.max(1.2f, Math.min(previous.fontSize, current.fontSize) * 0.18f);
      return gap > threshold;
    }
  }

  private record ParsedColumns(
      String branch,
      String productCode,
      String gradeX,
      String gradeY,
      String description,
      String balance,
      String cost,
      String total
  ) {
    boolean looksLikeDataRow() {
      return onlyDigits(branch).length() >= 1
          && !onlyDigits(productCode).isBlank()
          && !onlyDigits(gradeX).isBlank()
          && !onlyDigits(gradeY).isBlank();
    }
  }

  private static final class ColumnLayout {
    private final TextLine headerLine;
    private final float branch;
    private final float productCode;
    private final float gradeX;
    private final float gradeY;
    private final float description;
    private final float balance;
    private final float cost;
    private final float total;

    private ColumnLayout(
        TextLine headerLine,
        float branch,
        float productCode,
        float gradeX,
        float gradeY,
        float description,
        float balance,
        float cost,
        float total
    ) {
      this.headerLine = headerLine;
      this.branch = branch;
      this.productCode = productCode;
      this.gradeX = gradeX;
      this.gradeY = gradeY;
      this.description = description;
      this.balance = balance;
      this.cost = cost;
      this.total = total;
    }

    ParsedColumns read(TextLine line) {
      List<String> chunks = line.chunkTexts();
      String extractedCost = chunks.size() >= 2 ? chunks.get(chunks.size() - 2) : line.between(cost, total);
      String extractedTotal = chunks.isEmpty() ? line.between(total, Float.MAX_VALUE) : chunks.get(chunks.size() - 1);
      return new ParsedColumns(
          line.between(branch, productCode),
          line.between(productCode, gradeX),
          line.between(gradeX, gradeY),
          line.between(gradeY, midpoint(gradeY, description)),
          line.between(description, balance),
          line.between(balance, cost),
          extractedCost,
          extractedTotal
      );
    }

    private static float midpoint(float left, float right) {
      return left + (right - left) / 2f;
    }

    @Override
    public String toString() {
      return "filial=" + branch
          + " produto=" + productCode
          + " gradeX=" + gradeX
          + " gradeY=" + gradeY
          + " descricao=" + description
          + " saldo=" + balance
          + " custo=" + cost
          + " total=" + total;
    }
  }

  private static final class PositionedTextStripper extends PDFTextStripper {
    private final List<PageGlyphs> pages = new ArrayList<>();
    private PageGlyphs currentPage;
    private int chunk;

    private PositionedTextStripper() throws IOException {
      setSortByPosition(false);
      setShouldSeparateByBeads(false);
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
      currentPage = new PageGlyphs(pages.size() + 1, new ArrayList<>());
      pages.add(currentPage);
      chunk = 0;
      super.startPage(page);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
      chunk++;
      for (TextPosition position : textPositions) {
        String unicode = position.getUnicode();
        if (unicode == null || unicode.isEmpty()) continue;
        currentPage.glyphs.add(new Glyph(
            unicode,
            position.getXDirAdj(),
            position.getYDirAdj(),
            position.getWidthDirAdj(),
            position.getFontSizeInPt(),
            chunk
        ));
      }
    }
  }

  private static int intValue(Object value) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (RuntimeException error) {
      return 0;
    }
  }

  private static long longValue(Object value) {
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (RuntimeException error) {
      return 0;
    }
  }
}
