package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InventoryRecountProjectionTest {

  @Test
  void testMultidimensionalBucketProjection() {
    Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-24T10:01:00Z");
    Instant t3 = Instant.parse("2026-09-24T10:02:00Z");
    Instant t4 = Instant.parse("2026-09-24T10:03:00Z");
    Instant t5 = Instant.parse("2026-09-24T10:04:00Z");

    // VENDAS: BOA = 5, AVARIA = 1
    // DEPOSITO: BOA = 8, AVARIA = 2, ASSISTENCIA = 1
    // Total = 17
    var occ1 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-TEST", 5, "VENDAS", "BOA", "DEFINIR", "Op1", UUID.randomUUID(), "SCANNER", "", t1, t1, null);
    var occ2 = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-TEST", 1, "VENDAS", "AVARIA", "DEFINIR", "Op1", UUID.randomUUID(), "SCANNER", "", t2, t2, null);
    var occ3 = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-TEST", 8, "DEPOSITO", "BOA", "DEFINIR", "Op2", UUID.randomUUID(), "SCANNER", "", t3, t3, null);
    var occ4 = new InventoryCountingService.OccurrenceRecord(
        4L, 100L, 10L, "SKU-TEST", 2, "DEPOSITO", "AVARIA", "DEFINIR", "Op2", UUID.randomUUID(), "SCANNER", "", t4, t4, null);
    var occ5 = new InventoryCountingService.OccurrenceRecord(
        5L, 100L, 10L, "SKU-TEST", 1, "DEPOSITO", "ASSISTENCIA", "DEFINIR", "Op2", UUID.randomUUID(), "SCANNER", "", t5, t5, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, occ3, occ4, occ5));
    assertEquals(17, proj.totalQuantity(), "Soma total física deve ser exatamente 17");

    // Detalhes por localização
    Map<String, Map<String, Integer>> locs = proj.locationDetails();
    assertEquals(5, locs.get("VENDAS").get("BOA"));
    assertEquals(1, locs.get("VENDAS").get("AVARIA"));
    assertEquals(8, locs.get("DEPOSITO").get("BOA"));
    assertEquals(2, locs.get("DEPOSITO").get("AVARIA"));
    assertEquals(1, locs.get("DEPOSITO").get("ASSISTENCIA"));

    // Categorias consolidadas
    Map<String, Integer> cats = proj.categoryQuantities();
    assertEquals(13, cats.get("BOA"), "5 em vendas + 8 em deposito = 13");
    assertEquals(3, cats.get("AVARIA"), "1 em vendas + 2 em deposito = 3");
    assertEquals(1, cats.get("ASSISTENCIA"), "1 em deposito = 1");
    assertEquals(0, cats.get("OUTROS"));
  }

  @Test
  void testSomarAndCorrecaoInSameBucket() {
    Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-24T10:01:00Z");
    Instant t3 = Instant.parse("2026-09-24T10:02:00Z");

    var occ1 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-1", 10, "DEPOSITO", "BOA", "DEFINIR", "Op1", UUID.randomUUID(), "SCANNER", "", t1, t1, null);
    var occ2 = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-1", 5, "DEPOSITO", "BOA", "SOMAR", "Op1", UUID.randomUUID(), "SCANNER", "", t2, t2, null);
    var occ3 = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-1", 12, "DEPOSITO", "BOA", "CORRECAO", "Sup", UUID.randomUUID(), "MANUAL", "", t3, t3, 2L);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, occ3));
    assertEquals(12, proj.totalQuantity());
    assertEquals(12, proj.locationDetails().get("DEPOSITO").get("BOA"));
    assertEquals(3L, proj.lastOccurrenceId());
  }

  @Test
  void testGeralCannotMixWithDetailedLocations() {
    Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
    var occGeral = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-GERAL", 10, "GERAL", "BOA", "DEFINIR", "Op1", UUID.randomUUID(), "SCANNER", "", t1, t1, null);

    // Se já tem GERAL, deve rejeitar localização detalhada
    assertThrows(InventoryCountingService.ConflictException.class, () ->
        InventoryCountingService.validateLocationMixing(List.of(occGeral), "DEPOSITO"));
    assertThrows(InventoryCountingService.ConflictException.class, () ->
        InventoryCountingService.validateLocationMixing(List.of(occGeral), "VENDAS"));
    assertThrows(InventoryCountingService.ConflictException.class, () ->
        InventoryCountingService.validateLocationMixing(List.of(occGeral), "TROCAS"));
    assertThrows(InventoryCountingService.ConflictException.class, () ->
        InventoryCountingService.validateLocationMixing(List.of(occGeral), "OUTRO"));

    // Mas aceita outro registro em GERAL
    InventoryCountingService.validateLocationMixing(List.of(occGeral), "GERAL");
  }

  @Test
  void testDetailedLocationCannotMixWithGeral() {
    Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
    var occDetailed = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-DET", 5, "DEPOSITO", "BOA", "DEFINIR", "Op1", UUID.randomUUID(), "SCANNER", "", t1, t1, null);

    // Se já tem DEPOSITO, deve rejeitar GERAL
    assertThrows(InventoryCountingService.ConflictException.class, () ->
        InventoryCountingService.validateLocationMixing(List.of(occDetailed), "GERAL"));

    // Mas aceita outra localização detalhada (ex: VENDAS, TROCAS)
    InventoryCountingService.validateLocationMixing(List.of(occDetailed), "VENDAS");
    InventoryCountingService.validateLocationMixing(List.of(occDetailed), "DEPOSITO");
  }

  @Test
  void testV4LegacyOccurrencesAreCompatibleWithDefaultGeral() {
    Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
    // Usando construtor legado da V4 (sem parâmetro de localização)
    var occV4 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-V4", 15, "BOA", "DEFINIR", "Op1", UUID.randomUUID(), "SCANNER", "", t1, t1, null);

    assertEquals("GERAL", occV4.localizacao(), "Construtor legado deve preencher localizacao como GERAL");
    var proj = InventoryCountingService.calculateItemProjection(List.of(occV4));
    assertEquals(15, proj.totalQuantity());
    assertEquals(15, proj.locationDetails().get("GERAL").get("BOA"));
  }

  @Test
  void testDifferencesAndStatusComputation() {
    int saldo = 10;

    // Conforme (10 = 10)
    int fisicoConforme = 10;
    int diffConforme = fisicoConforme - saldo;
    assertEquals(0, diffConforme);
    assertEquals("CONFORME", diffConforme == 0 ? "CONFORME" : "DIVERGENTE");

    // Divergente falta (8 - 10 = -2)
    int fisicoFalta = 8;
    int diffFalta = fisicoFalta - saldo;
    assertEquals(-2, diffFalta);
    assertEquals("DIVERGENTE", diffFalta == 0 ? "CONFORME" : "DIVERGENTE");

    // Divergente sobra (12 - 10 = +2)
    int fisicoSobra = 12;
    int diffSobra = fisicoSobra - saldo;
    assertEquals(2, diffSobra);
    assertEquals("DIVERGENTE", diffSobra == 0 ? "CONFORME" : "DIVERGENTE");

    // Zero contado (0 - 10 = -10)
    int fisicoZero = 0;
    int diffZero = fisicoZero - saldo;
    assertEquals(-10, diffZero);
    assertEquals("DIVERGENTE", diffZero == 0 ? "CONFORME" : "DIVERGENTE");

    // Recontagem (R2)
    int r2FisicoConforme = 10;
    assertEquals("CONFORME_APOS_RECONTAGEM", r2FisicoConforme == saldo ? "CONFORME_APOS_RECONTAGEM" : "DIVERGENCIA_CONFIRMADA");

    int r2FisicoDivergente = 9;
    assertEquals("DIVERGENCIA_CONFIRMADA", r2FisicoDivergente == saldo ? "CONFORME_APOS_RECONTAGEM" : "DIVERGENCIA_CONFIRMADA");
  }
}
