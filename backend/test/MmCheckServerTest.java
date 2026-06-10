import java.util.List;
import java.util.Map;

public class MmCheckServerTest {
  public static void main(String[] args) {
    shouldNormalizeAndDetectDuplicateRows();
    shouldRejectConflictingBalances();
    System.out.println("MmCheckServerTest: OK");
  }

  private static void shouldNormalizeAndDetectDuplicateRows() {
    Map<String, Object> analysis = Map.of("rows", List.of(
        row("73578", "1", "2", 12),
        row("73578", "1", "2", 12),
        row("9999999", "1", "2", 999)
    ));

    MmCheckServer.CountImportResult result = MmCheckServer.countItemsFromAnalysis(analysis);
    require(result.items().size() == 1, "deve consolidar SKU duplicado");
    require("73578-1.2".equals(result.items().get(0).sku()), "deve montar produto-gradeX.gradeY");
    require(result.items().get(0).system() == 12, "deve preservar o saldo");
    require(result.warnings().size() == 1, "deve informar duplicidade");
  }

  private static void shouldRejectConflictingBalances() {
    Map<String, Object> analysis = Map.of("rows", List.of(
        row("75480", "1", "2", 8),
        row("75480", "1", "2", 9)
    ));

    try {
      MmCheckServer.countItemsFromAnalysis(analysis);
      throw new AssertionError("deveria rejeitar saldo conflitante");
    } catch (RuntimeException expected) {
      require(expected.getMessage().contains("saldos diferentes"), "deve explicar o conflito");
    }
  }

  private static Map<String, Object> row(String product, String gradeX, String gradeY, int balance) {
    return Map.of(
        "productCode", product,
        "gradeX", gradeX,
        "gradeY", gradeY,
        "balance", balance
    );
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
