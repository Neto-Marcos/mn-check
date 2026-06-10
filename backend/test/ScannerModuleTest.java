package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

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

  @Test
  void decodesCode128ImageWithZxing() throws Exception {
    BufferedImage image = MatrixToImageWriter.toBufferedImage(
        new MultiFormatWriter().encode("7426613", BarcodeFormat.CODE_128, 720, 220)
    );
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    MockMultipartFile file = new MockMultipartFile(
        "file", "etiqueta.png", "image/png", output.toByteArray()
    );

    assertEquals("7426613", new BarcodeDecoder().decodeCode128(file));
  }

  @Test
  void decodesRotatedCode128Photo() throws Exception {
    BufferedImage barcode = MatrixToImageWriter.toBufferedImage(
        new MultiFormatWriter().encode("7426613", BarcodeFormat.CODE_128, 720, 220)
    );
    BufferedImage rotated = new BufferedImage(220, 720, BufferedImage.TYPE_INT_RGB);
    var graphics = rotated.createGraphics();
    graphics.translate(220, 0);
    graphics.rotate(Math.PI / 2);
    graphics.drawImage(barcode, 0, 0, null);
    graphics.dispose();

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(rotated, "jpg", output);
    MockMultipartFile file = new MockMultipartFile(
        "file", "etiqueta-vertical.jpg", "image/jpeg", output.toByteArray()
    );

    assertEquals("7426613", new BarcodeDecoder().decodeCode128(file));
  }
}
