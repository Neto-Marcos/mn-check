package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ScannerModuleTest {
  private final BarcodeValidationService validationService = new BarcodeValidationService();

  @Test
  void normalizesIndustrialCode128Payload() {
    assertEquals("74266.1.3", BarcodeParser.parse("7426613").normalized());
    assertEquals("74266.1.3", BarcodeParser.parse("74266 1 3").normalized());
    assertEquals("74266.1.3", BarcodeParser.parse("74266.1.3").normalized());
  }

  @Test
  void blocksWrongVoltageWithSpecificReason() {
    BarcodeValidationService.ValidationResult result =
        validationService.validate("74266.1.3", "7426612");

    assertFalse(result.approved());
    assertEquals("BLOQUEADO", result.status());
    assertEquals("Voltagem incorreta", result.reason());
  }

  @Test
  void mapsIndustrialVoltageCodes() {
    assertEquals("Bivolt", BarcodeParser.parse("7426610").voltageType().label());
    assertEquals("Bivolt", BarcodeParser.parse("7426614").voltageType().label());
    assertEquals("127V", BarcodeParser.parse("7426611").voltageType().label());
    assertEquals("127V", BarcodeParser.parse("7426613").voltageType().label());
    assertEquals("220V", BarcodeParser.parse("7426612").voltageType().label());
  }

  @Test
  void approvesEquivalent127VoltageCodes() {
    BarcodeValidationService.ValidationResult result =
        validationService.validate("74266.1.3", "7426611");

    assertTrue(result.approved());
    assertEquals("APROVADO", result.status());
  }

  @Test
  void approvesMatchingProduct() {
    BarcodeValidationService.ValidationResult result =
        validationService.validate("74266.1.3", "74266 1 3");

    assertTrue(result.approved());
    assertEquals("APROVADO", result.status());
  }

}
