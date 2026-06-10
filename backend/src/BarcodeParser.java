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

    public VoltageType voltageType() {
      return VoltageType.fromCode(voltage);
    }
  }

  public enum VoltageType {
    BIVOLT("Bivolt"),
    V127("127V"),
    V220("220V");

    private final String label;

    VoltageType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    static VoltageType fromCode(String code) {
      return switch (code) {
        case "0", "4" -> BIVOLT;
        case "1", "3" -> V127;
        case "2" -> V220;
        default -> throw new InvalidBarcodeException("Código de voltagem inválido: " + code);
      };
    }
  }

  public static final class InvalidBarcodeException extends RuntimeException {
    InvalidBarcodeException(String message) {
      super(message);
    }
  }
}
