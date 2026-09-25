package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InventoryCompoundCountingUnitTest {

  @Test
  void testCompoundCountingMath() {
    int total = 10;
    int avaria = 2;
    int assistencia = 1;
    int outros = 0;
    int boa = total - (avaria + assistencia + outros);
    assertEquals(7, boa, "Boa deve ser 7 quando Total=10, Avaria=2, Assistência=1");

    Instant now = Instant.now();
    var occBoa = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-COMP", boa, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var occAvaria = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-COMP", avaria, "GERAL", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var occAssistencia = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-COMP", assistencia, "GERAL", "ASSISTENCIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occBoa, occAvaria, occAssistencia));
    assertEquals(10, proj.totalQuantity(), "Total projetado deve ser 10");
    assertEquals(7, proj.categoryQuantities().get("BOA"));
    assertEquals(2, proj.categoryQuantities().get("AVARIA"));
    assertEquals(1, proj.categoryQuantities().get("ASSISTENCIA"));
    assertEquals(0, proj.categoryQuantities().get("OUTROS"));
  }

  @Test
  void testCompoundCountingZeroTotal() {
    int total = 0;
    int avaria = 0;
    int assistencia = 0;
    int outros = 0;
    int boa = total - (avaria + assistencia + outros);
    assertEquals(0, boa);

    Instant now = Instant.now();
    var occZero = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-ZERO", 0, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occZero));
    assertEquals(0, proj.totalQuantity(), "Item contado com zero explícito deve ter total 0");
    assertEquals(0, proj.categoryQuantities().get("BOA"));
    assertTrue(!proj.locationDetails().isEmpty(), "Localização deve estar presente mesmo com contagem zero");
  }

  @Test
  void testCompoundCountingCorrectionSemantics() {
    Instant t1 = Instant.parse("2026-09-25T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-25T10:05:00Z");

    // Initial count: Total 10 (Boa 7, Avaria 2, Assistência 1)
    var occ1 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-CORR", 7, "DEPOSITO", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", t1, t1, null);
    var occ2 = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-CORR", 2, "DEPOSITO", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", t1, t1, null);
    var occ3 = InventoryCountingRecordMock(3L, 100L, 10L, "SKU-CORR", 1, "DEPOSITO", "ASSISTENCIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", t1, t1, null);

    // Correction: Operator changes Avaria 2 -> 3 (Total 10, Avaria 3, Assistência 1, Boa 6)
    var corr1 = new InventoryCountingService.OccurrenceRecord(
        4L, 100L, 10L, "SKU-CORR", 6, "DEPOSITO", "BOA", "CORRECAO", "Op", UUID.randomUUID(), "MANUAL", "", t2, t2, null);
    var corr2 = new InventoryCountingService.OccurrenceRecord(
        5L, 100L, 10L, "SKU-CORR", 3, "DEPOSITO", "AVARIA", "CORRECAO", "Op", UUID.randomUUID(), "MANUAL", "", t2, t2, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, occ3, corr1, corr2));
    assertEquals(10, proj.totalQuantity(), "Total após correção deve permanecer 10");
    assertEquals(6, proj.locationDetails().get("DEPOSITO").get("BOA"));
    assertEquals(3, proj.locationDetails().get("DEPOSITO").get("AVARIA"));
    assertEquals(1, proj.locationDetails().get("DEPOSITO").get("ASSISTENCIA"));
  }

  @Test
  void testCompoundCountingLocationIsolation() {
    Instant now = Instant.now();
    // VENDAS: Boa 4, Avaria 1 (Total 5)
    var v1 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-LOC", 4, "VENDAS", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var v2 = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-LOC", 1, "VENDAS", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    // DEPOSITO: Boa 7, Avaria 2, Assistencia 1 (Total 10)
    var d1 = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-LOC", 7, "DEPOSITO", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var d2 = new InventoryCountingService.OccurrenceRecord(
        4L, 100L, 10L, "SKU-LOC", 2, "DEPOSITO", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var d3 = new InventoryCountingService.OccurrenceRecord(
        5L, 100L, 10L, "SKU-LOC", 1, "DEPOSITO", "ASSISTENCIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(v1, v2, d1, d2, d3));
    assertEquals(15, proj.totalQuantity(), "Soma total deve ser 15");
    assertEquals(5, proj.locationDetails().get("VENDAS").values().stream().mapToInt(Integer::intValue).sum());
    assertEquals(10, proj.locationDetails().get("DEPOSITO").values().stream().mapToInt(Integer::intValue).sum());
    assertEquals(11, proj.categoryQuantities().get("BOA"), "4 em vendas + 7 em deposito = 11");
  }

  // Helper
  private static InventoryCountingService.OccurrenceRecord InventoryCountingRecordMock(
      long id, long roundId, long itemId, String sku, int qtd, String loc, String cat, String act,
      String op, UUID eventId, String ori, String dev, Instant cTs, Instant sTs, Long refId) {
    return new InventoryCountingService.OccurrenceRecord(id, roundId, itemId, sku, qtd, loc, cat, act, op, eventId, ori, dev, cTs, sTs, refId);
  }
}
