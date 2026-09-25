package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InventoryClosingUnitTest {

  @Test
  void physicalQuantityPriorityRules() {
    // 1. R2 existe e foi contado -> R2 prevalece
    Integer r2Contado = 12;
    Integer r1Contado = 10;
    Integer qtdFinalComR2 = resolvePhysicalQuantity(true, true, r2Contado, true, r1Contado);
    assertEquals(12, qtdFinalComR2);

    // 2. R2 existe mas NÃO foi contado -> Deve ser NULL, NÃO inventar 0!
    Integer qtdFinalR2NaoContado = resolvePhysicalQuantity(true, false, null, true, r1Contado);
    assertNull(qtdFinalR2NaoContado, "Se o item foi enviado para R2 e não foi contado, quantidade física deve ser null!");

    // 3. Não houve R2, R1 contado -> R1 prevalece
    Integer qtdFinalSemR2Contado = resolvePhysicalQuantity(false, false, null, true, r1Contado);
    assertEquals(10, qtdFinalSemR2Contado);

    // 4. Não houve R2, R1 NÃO contado -> Deve ser NULL, NÃO inventar 0!
    Integer qtdFinalSemR2NaoContado = resolvePhysicalQuantity(false, false, null, false, null);
    assertNull(qtdFinalSemR2NaoContado, "Item não contado na R1 sem R2 deve ter quantidade física null!");

    // 5. Contado 0 explicitamente (ex: prateleira vazia confirmada)
    Integer qtdFinalZeroExplicito = resolvePhysicalQuantity(false, false, null, true, 0);
    assertEquals(0, qtdFinalZeroExplicito);
  }

  @Test
  void differenceCalculationAndDivergenceClassification() {
    int saldoSnapshot = 10;

    // Falta: físico = 8, saldo = 10 -> diff = -2, FALTA, DIVERGENCIA_CONFIRMADA
    DifferenceResult falta = calculateDifference(8, saldoSnapshot, false);
    assertEquals(-2, falta.diferenca());
    assertEquals("FALTA", falta.tipoDivergencia());
    assertEquals("DIVERGENCIA_CONFIRMADA", falta.estadoFinal());

    // Sobra: físico = 13, saldo = 10 -> diff = +3, SOBRA, DIVERGENCIA_CONFIRMADA
    DifferenceResult sobra = calculateDifference(13, saldoSnapshot, false);
    assertEquals(3, sobra.diferenca());
    assertEquals("SOBRA", sobra.tipoDivergencia());
    assertEquals("DIVERGENCIA_CONFIRMADA", sobra.estadoFinal());

    // Conforme R1: físico = 10, saldo = 10, sem R2 -> diff = 0, CONFORME
    DifferenceResult conformeR1 = calculateDifference(10, saldoSnapshot, false);
    assertEquals(0, conformeR1.diferenca());
    assertEquals("CONFORME", conformeR1.tipoDivergencia());
    assertEquals("CONFORME", conformeR1.estadoFinal());

    // Conforme após R2: físico = 10, saldo = 10, com R2 -> diff = 0, CONFORME_APOS_RECONTAGEM
    DifferenceResult conformeR2 = calculateDifference(10, saldoSnapshot, true);
    assertEquals(0, conformeR2.diferenca());
    assertEquals("CONFORME", conformeR2.tipoDivergencia());
    assertEquals("CONFORME_APOS_RECONTAGEM", conformeR2.estadoFinal());

    // Não contado: físico = null -> diff = null, NAO_CONTADO, NAO_CONTADO (NÃO classificar como FALTA!)
    DifferenceResult naoContado = calculateDifference(null, saldoSnapshot, false);
    assertNull(naoContado.diferenca());
    assertEquals("NAO_CONTADO", naoContado.tipoDivergencia());
    assertEquals("NAO_CONTADO", naoContado.estadoFinal());

    // Zero contado explicitamente: físico = 0, saldo = 5 -> diff = -5, FALTA
    DifferenceResult zeroExplicito = calculateDifference(0, 5, true);
    assertEquals(-5, zeroExplicito.diferenca());
    assertEquals("FALTA", zeroExplicito.tipoDivergencia());
    assertEquals("DIVERGENCIA_CONFIRMADA", zeroExplicito.estadoFinal());
  }

  @Test
  void aggregatedQuantitiesMustBePositiveMagnitude() {
    List<DifferenceResult> results = List.of(
        calculateDifference(8, 10, false),   // falta 2 (-2)
        calculateDifference(0, 5, false),    // falta 5 (-5)
        calculateDifference(14, 10, false),  // sobra 4 (+4)
        calculateDifference(10, 10, false),  // conforme (0)
        calculateDifference(null, 3, false)  // nao contado (null)
    );

    int quantidadeFalta = 0;
    int quantidadeSobra = 0;
    int itensComFalta = 0;
    int itensComSobra = 0;

    for (DifferenceResult res : results) {
      if ("FALTA".equals(res.tipoDivergencia())) {
        itensComFalta++;
        quantidadeFalta += Math.abs(res.diferenca());
      } else if ("SOBRA".equals(res.tipoDivergencia())) {
        itensComSobra++;
        quantidadeSobra += res.diferenca();
      }
    }

    assertEquals(2, itensComFalta);
    assertEquals(1, itensComSobra);
    assertEquals(7, quantidadeFalta, "A quantidade de falta deve ser representada como magnitude estritamente positiva (Math.abs)");
    assertEquals(4, quantidadeSobra);
  }

  @Test
  void exceptionalClosingAuthorizationAndValidation() {
    InventoryClosingService service = new InventoryClosingService("jdbc:postgresql://localhost:5432/mock");

    // Usuário sem role admin deve ser rejeitado com 403 ForbiddenException
    LegacyAuthenticationClient.AuthenticatedUser stockUser =
        new LegacyAuthenticationClient.AuthenticatedUser("user-1", "Operador", "stock");

    InventoryClosingService.CloseCommand cmdExcepcionalValido =
        new InventoryClosingService.CloseCommand("EXCEPCIONAL", "Justificativa auditável com mais de quinze caracteres.");

    InventoryClosingService.ForbiddenException exRole = assertThrows(
        InventoryClosingService.ForbiddenException.class,
        () -> service.closeInventory(1L, "281", cmdExcepcionalValido, stockUser)
    );
    assertEquals(403, exRole.status());
    assertTrue(exRole.getMessage().contains("Apenas administradores"));

    // Usuário admin mas com justificativa vazia ou curta (< 15 caracteres) deve ser rejeitado com 400
    LegacyAuthenticationClient.AuthenticatedUser adminUser =
        new LegacyAuthenticationClient.AuthenticatedUser("admin-1", "Administrador", "admin");

    InventoryClosingService.CloseCommand cmdCurto =
        new InventoryClosingService.CloseCommand("EXCEPCIONAL", "Curto");

    InventoryClosingService.ValidationException exJust = assertThrows(
        InventoryClosingService.ValidationException.class,
        () -> service.closeInventory(1L, "281", cmdCurto, adminUser)
    );
    assertEquals(400, exJust.status());
    assertTrue(exJust.getMessage().contains("mínimo 15 caracteres"));
  }

  @Test
  void closingTypesValidation() {
    assertTrue(InventoryClosingService.CLOSING_TYPES.contains("NORMAL"));
    assertTrue(InventoryClosingService.CLOSING_TYPES.contains("EXCEPCIONAL"));
    assertFalse(InventoryClosingService.CLOSING_TYPES.contains("FORCADO"));
    assertFalse(InventoryClosingService.CLOSING_TYPES.contains("AUTOMATICO"));
  }

  // Helpers de lógica pura
  private Integer resolvePhysicalQuantity(
      boolean houveR2,
      boolean r2Contado,
      Integer r2Fisico,
      boolean r1Contado,
      Integer r1Fisico
  ) {
    if (houveR2) {
      return r2Contado ? r2Fisico : null;
    }
    return r1Contado ? r1Fisico : null;
  }

  private record DifferenceResult(Integer diferenca, String tipoDivergencia, String estadoFinal) {}

  private DifferenceResult calculateDifference(Integer fisico, int saldoSnapshot, boolean houveR2) {
    if (fisico == null) {
      return new DifferenceResult(null, "NAO_CONTADO", "NAO_CONTADO");
    }
    int diff = fisico - saldoSnapshot;
    if (diff == 0) {
      return new DifferenceResult(0, "CONFORME", houveR2 ? "CONFORME_APOS_RECONTAGEM" : "CONFORME");
    }
    String tipo = diff < 0 ? "FALTA" : "SOBRA";
    return new DifferenceResult(diff, tipo, "DIVERGENCIA_CONFIRMADA");
  }
}
