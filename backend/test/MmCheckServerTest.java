package br.com.mncheck;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

public class MmCheckServerTest {
  public static void main(String[] args) throws Exception {
    shouldReadAllPagesAndApplyBalanceRules();
    shouldSumDuplicateBalances();
    shouldRebuildBrokenRows();
    if (args.length > 0) shouldReadRealBalancePdf(Path.of(args[0]));
    System.out.println("MmCheckServerTest: OK");
  }

  @Test
  void parserReadsEveryPageAndAppliesRules() throws Exception {
    shouldReadAllPagesAndApplyBalanceRules();
  }

  @Test
  void parserSumsDuplicateBalances() throws Exception {
    shouldSumDuplicateBalances();
  }

  @Test
  void parserRebuildsRowsBrokenAcrossLines() throws Exception {
    shouldRebuildBrokenRows();
  }

  private static void shouldReadAllPagesAndApplyBalanceRules() throws Exception {
    byte[] pdf = balancePdf(List.of(
        List.of(
            row("281", "73578", "1", "2", "LAVADORA MIDEA 13KG", "406"),
            row("281", "9999999", "1", "2", "CABECALHO INVALIDO", "999"),
            row("281", "73578", "1", "2", "LAVADORA MIDEA 13KG", "406")
        ),
        List.of(
            row("281", "75480", "1", "2", "REFRIGERADOR MIDEA 394L", "108"),
            row("281", "73001", "1", "2", "REFRIGERADOR ESMALTEC 276L RCD34", "1"),
            row("281", "76331", "3", "4", "CAIXA DE SOM PHILIPS PARTY SPEAKER1 B1T2 1500W TAX40", "112")
        )
    ));

    BalancePdfParser.Result result = BalancePdfParser.parse(pdf);
    require(result.metrics().pagesProcessed() == 2, "deve ler todas as folhas");
    require(result.rows().size() == 4, "deve ignorar código falso e consolidar duplicidade");
    require(result.metrics().duplicateSkus() == 1, "deve contar SKU duplicado");
    require(result.metrics().conflictsFound() == 0, "duplicidade deve ser somada, não bloqueada");
    require(result.metrics().totalLinesRead() > 0, "deve registrar o total de linhas lidas");
    require(balanceOf(result, "73578.1.2") == 812, "deve somar saldos do SKU duplicado");
    require(balanceOf(result, "75480.1.2") == 108, "deve separar descrição numérica do saldo");
    require(balanceOf(result, "73001.1.2") == 1, "deve corrigir saldo contaminado por dígitos da descrição");
    int targetBalance = balanceOf(result, "76331.3.4");
    require(targetBalance == 112, "deve importar 76331.3.4 com descrição sobreposta; saldo=" + targetBalance);
    require(result.debugReport().contains("76331.3.4 = 112"), "debug deve conter o produto processado");
    require(!result.ignored().isEmpty(), "deve detalhar linhas ignoradas");
  }

  private static void shouldSumDuplicateBalances() throws Exception {
    byte[] pdf = balancePdf(List.of(List.of(
        row("281", "74683", "1", "2", "REFRIGERADOR CONSUL 377L", "222"),
        row("281", "74683", "1", "2", "REFRIGERADOR CONSUL 377L", "221")
    )));

    BalancePdfParser.Result result = BalancePdfParser.parse(pdf);
    require(result.metrics().duplicateSkus() == 1, "deve detectar SKU duplicado");
    require(balanceOf(result, "74683.1.2") == 443, "deve somar os saldos duplicados");
    require(result.conflicts().isEmpty(), "duplicidade não deve impedir a importação");
  }

  private static void shouldRebuildBrokenRows() throws Exception {
    BalancePdfParser.Result result = BalancePdfParser.parse(brokenRowPdf());
    require(balanceOf(result, "76331.3.4") == 112, "deve reconstruir produto quebrado em duas linhas");
    require(result.debugReport().contains("[RECONSTRUÍDA]"), "debug deve registrar a reconstrução da linha");
  }

  private static void shouldReadRealBalancePdf(Path pdf) throws Exception {
    require(Files.isRegularFile(pdf), "PDF real não encontrado: " + pdf);
    BalancePdfParser.Result result = BalancePdfParser.parse(Files.readAllBytes(pdf));
    System.out.println("REAL_PDF"
        + " paginas=" + result.metrics().pagesProcessed()
        + " skus=" + result.metrics().skusRead()
        + " linhas_ignoradas=" + result.metrics().ignoredLines()
        + " duplicados=" + result.metrics().duplicateSkus()
        + " conflitos=" + result.metrics().conflictsFound()
        + " duracao_ms=" + result.metrics().elapsedMs());
    require(result.metrics().pagesProcessed() == 5, "PDF real deve ter cinco folhas");
    require(result.rows().size() > 230, "PDF real deve conter mais de 230 SKUs");
    require(result.conflicts().isEmpty(), "PDF real não deve conter conflitos");
    require(balanceOf(result, "73578.1.2") == 406, "saldo real de 73578.1.2 incorreto");
    require(balanceOf(result, "74683.1.2") == 222, "saldo real de 74683.1.2 incorreto");
    require(balanceOf(result, "75480.1.2") == 108, "saldo real de 75480.1.2 incorreto");
    require(balanceOf(result, "76331.3.4") == 112, "saldo real de 76331.3.4 incorreto");
  }

  private static int balanceOf(BalancePdfParser.Result result, String sku) {
    return result.rows().stream()
        .filter(row -> row.sku().equals(sku))
        .findFirst()
        .orElseThrow(() -> new AssertionError("SKU não encontrado: " + sku))
        .balance();
  }

  private static TestRow row(
      String branch,
      String product,
      String gradeX,
      String gradeY,
      String description,
      String balance
  ) {
    return new TestRow(branch, product, gradeX, gradeY, description, balance);
  }

  private static byte[] balancePdf(List<List<TestRow>> pages) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          text(content, font, 36, 760, "Folha : " + (pageIndex + 1));
          text(content, font, 36, 735, "Cod Filial");
          text(content, font, 95, 735, "Cod Produto");
          text(content, font, 175, 735, "Grade 'X'");
          text(content, font, 230, 735, "Grade 'Y'");
          text(content, font, 285, 735, "Produto");
          text(content, font, 445, 735, "Saldo");
          text(content, font, 490, 735, "Custo Medio");
          text(content, font, 565, 735, "Total");

          float y = 710;
          for (TestRow row : pages.get(pageIndex)) {
            text(content, font, 36, y, row.branch);
            text(content, font, 95, y, row.product);
            text(content, font, 175, y, row.gradeX);
            text(content, font, 230, y, row.gradeY);
            text(content, font, 285, y, row.description);
            text(content, font, 445, y, row.balance);
            text(content, font, 490, y, "100,00");
            text(content, font, 565, y, totalFor(row.balance));
            y -= 18;
          }
          text(content, font, 36, y - 8, "Total Geral 999999");
        }
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      document.save(output);
      return output.toByteArray();
    }
  }

  private static byte[] brokenRowPdf() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        text(content, font, 36, 760, "Folha : 1");
        text(content, font, 36, 735, "Cod Filial");
        text(content, font, 95, 735, "Cod Produto");
        text(content, font, 175, 735, "Grade 'X'");
        text(content, font, 230, 735, "Grade 'Y'");
        text(content, font, 285, 735, "Produto");
        text(content, font, 445, 735, "Saldo");
        text(content, font, 490, 735, "Custo Medio");
        text(content, font, 565, 735, "Total");
        text(content, font, 36, 710, "281");
        text(content, font, 95, 710, "76331");
        text(content, font, 175, 700, "3");
        text(content, font, 230, 700, "4");
        text(content, font, 285, 700, "CAIXA DE SOM PHILIPS");
        text(content, font, 445, 700, "112");
        text(content, font, 490, 700, "480,88");
        text(content, font, 565, 700, "53858,56");
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      document.save(output);
      return output.toByteArray();
    }
  }

  private static String totalFor(String balance) {
    String[] groups = balance.trim().split("\\s+");
    int value = Integer.parseInt(groups[groups.length - 1]);
    return (value * 100) + ",00";
  }

  private static void text(
      PDPageContentStream content,
      PDType1Font font,
      float x,
      float y,
      String value
  ) throws Exception {
    content.beginText();
    content.setFont(font, 8);
    content.newLineAtOffset(x, y);
    content.showText(value);
    content.endText();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private record TestRow(
      String branch,
      String product,
      String gradeX,
      String gradeY,
      String description,
      String balance
  ) {}
}
