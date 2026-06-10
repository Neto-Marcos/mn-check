package br.com.mncheck;

public final class BarcodeParser {
  private BarcodeParser() {}

  public static ProductCode parse(String rawCode) {
    String digits = rawCode == null ? "" : rawCode.replaceAll("\\D", "");
    if (digits.length() != 7) {
      throw new InvalidBarcodeException(
          "Código inválido. O CODE 128 deve conter SKU de 5 dígitos, cor e voltagem."
      );
    }
    return new ProductCode(
        digits.substring(0, 5),
        digits.substring(5, 6),
        digits.substring(6, 7)
    );
  }

  public record ProductCode(String sku, String color, String voltage) {
    public String normalized() {
      return sku + "." + color + "." + voltage;
    }

    public String digits() {
      return sku + color + voltage;
    }
  }

  public static final class InvalidBarcodeException extends RuntimeException {
    InvalidBarcodeException(String message) {
      super(message);
    }
  }
}
