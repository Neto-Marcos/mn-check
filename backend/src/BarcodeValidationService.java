package br.com.mncheck;

import org.springframework.stereotype.Service;

@Service
public class BarcodeValidationService {
  public ValidationResult validate(String expectedRaw, String scannedRaw) {
    BarcodeParser.ProductCode expected = BarcodeParser.parse(expectedRaw);
    BarcodeParser.ProductCode scanned = BarcodeParser.parse(scannedRaw);

    if (!expected.sku().equals(scanned.sku())) {
      return ValidationResult.blocked(expected, scanned, "SKU incorreto");
    }
    if (!expected.color().equals(scanned.color())) {
      return ValidationResult.blocked(expected, scanned, "Cor incorreta");
    }
    if (!expected.voltage().equals(scanned.voltage())) {
      return ValidationResult.blocked(expected, scanned, "Voltagem incorreta");
    }
    return ValidationResult.approved(expected, scanned);
  }

  public record ValidationResult(
      boolean approved,
      String status,
      String reason,
      BarcodeParser.ProductCode expected,
      BarcodeParser.ProductCode scanned
  ) {
    static ValidationResult approved(
        BarcodeParser.ProductCode expected,
        BarcodeParser.ProductCode scanned
    ) {
      return new ValidationResult(true, "APROVADO", "Produto correto", expected, scanned);
    }

    static ValidationResult blocked(
        BarcodeParser.ProductCode expected,
        BarcodeParser.ProductCode scanned,
        String reason
    ) {
      return new ValidationResult(false, "BLOQUEADO", reason, expected, scanned);
    }
  }
}
