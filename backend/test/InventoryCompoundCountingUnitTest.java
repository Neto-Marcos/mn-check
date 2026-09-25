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

  @Test
  void testNewUxBoaFirstMath() {
    // Nova regra: BOA é editável, TOTAL é calculado
    int boa = 10;
    int avaria = 1;
    int assistencia = 0;
    int outros = 0;
    int total = boa + avaria + assistencia + outros;
    assertEquals(11, total, "BOA 10 + AVARIA 1 deve resultar em TOTAL 11");

    Instant now = Instant.now();
    var occBoa = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-BOA1", boa, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var occAvaria = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-BOA1", avaria, "GERAL", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occBoa, occAvaria));
    assertEquals(11, proj.totalQuantity(), "Total físico projetado deve ser 11");
    assertEquals(10, proj.categoryQuantities().get("BOA"));
    assertEquals(1, proj.categoryQuantities().get("AVARIA"));
    assertEquals(0, proj.categoryQuantities().get("ASSISTENCIA"));
    assertEquals(0, proj.categoryQuantities().get("OUTROS"));
  }

  @Test
  void testBoaPlusAvariaPlusAssistencia() {
    int boa = 10;
    int avaria = 1;
    int assistencia = 2;
    int outros = 0;
    int total = boa + avaria + assistencia + outros;
    assertEquals(13, total, "BOA 10 + AVARIA 1 + ASSISTENCIA 2 deve resultar em TOTAL 13");

    Instant now = Instant.now();
    var occBoa = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-BOA2", boa, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var occAvaria = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-BOA2", avaria, "GERAL", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var occAssistencia = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-BOA2", assistencia, "GERAL", "ASSISTENCIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occBoa, occAvaria, occAssistencia));
    assertEquals(13, proj.totalQuantity(), "Total físico projetado deve ser 13");
    assertEquals(10, proj.categoryQuantities().get("BOA"));
    assertEquals(1, proj.categoryQuantities().get("AVARIA"));
    assertEquals(2, proj.categoryQuantities().get("ASSISTENCIA"));
  }

  @Test
  void testZeroBoaWithAvaria() {
    int boa = 0;
    int avaria = 2;
    int total = boa + avaria;
    assertEquals(2, total, "BOA 0 + AVARIA 2 deve resultar em TOTAL 2");

    Instant now = Instant.now();
    var occBoa = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-AV2", boa, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);
    var occAvaria = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-AV2", avaria, "GERAL", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occBoa, occAvaria));
    assertEquals(2, proj.totalQuantity(), "Total físico deve ser 2");
    assertEquals(0, proj.categoryQuantities().get("BOA"));
    assertEquals(2, proj.categoryQuantities().get("AVARIA"));
  }

  @Test
  void testAllZeroExplicitCountPreservesContado() {
    int boa = 0;
    int avaria = 0;
    int assistencia = 0;
    int outros = 0;
    int total = boa + avaria + assistencia + outros;
    assertEquals(0, total);

    Instant now = Instant.now();
    var occZero = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-ALLZERO", 0, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", now, now, null);

    var proj = InventoryCountingService.calculateItemProjection(List.of(occZero));
    assertEquals(0, proj.totalQuantity(), "Total físico 0");
    assertEquals(0, proj.categoryQuantities().get("BOA"));
    assertTrue(!proj.locationDetails().isEmpty(), "Localização GERAL deve estar registrada");
  }

  @Test
  void testReopenAndChangeConditionIncreasesTotal() {
    Instant t1 = Instant.parse("2026-09-25T11:00:00Z");
    Instant t2 = Instant.parse("2026-09-25T11:05:00Z");

    // Contagem inicial: BOA 10, AVARIA 1 => TOTAL 11
    var occ1 = new InventoryCountingService.OccurrenceRecord(
        1L, 100L, 10L, "SKU-REOPEN", 10, "GERAL", "BOA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", t1, t1, null);
    var occ2 = new InventoryCountingService.OccurrenceRecord(
        2L, 100L, 10L, "SKU-REOPEN", 1, "GERAL", "AVARIA", "DEFINIR", "Op", UUID.randomUUID(), "MANUAL", "", t1, t1, null);

    var projInitial = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2));
    assertEquals(11, projInitial.totalQuantity(), "Total inicial deve ser 11");

    // Reabertura: Operador encontra mais 1 avaria (AVARIA passa de 1 para 2). BOA continua 10!
    // Total esperado: 10 + 2 = 12
    var corrBoa = new InventoryCountingService.OccurrenceRecord(
        3L, 100L, 10L, "SKU-REOPEN", 10, "GERAL", "BOA", "CORRECAO", "Op", UUID.randomUUID(), "MANUAL", "", t2, t2, null);
    var corrAvaria = new InventoryCountingService.OccurrenceRecord(
        4L, 100L, 10L, "SKU-REOPEN", 2, "GERAL", "AVARIA", "CORRECAO", "Op", UUID.randomUUID(), "MANUAL", "", t2, t2, null);

    var projReopened = InventoryCountingService.calculateItemProjection(List.of(occ1, occ2, corrBoa, corrAvaria));
    assertEquals(12, projReopened.totalQuantity(), "Total após correção deve ser 12 (BOA=10, AVARIA=2)");
    assertEquals(10, projReopened.categoryQuantities().get("BOA"));
    assertEquals(2, projReopened.categoryQuantities().get("AVARIA"));
  }

  @Test
  void testCommandConstructors() {
    UUID eventId = UUID.randomUUID();
    // Test new constructor with boa
    var cmdWithBoa = new InventoryCountingService.RecordCompoundCommand(
        "SKU-1", "GERAL", 11, 10, 1, 0, 0, eventId, "MANUAL", "PWA", Instant.now(), null);
    assertEquals(11, cmdWithBoa.total());
    assertEquals(10, cmdWithBoa.boa());
    assertEquals(1, cmdWithBoa.avaria());

    // Test backward compatible constructor without boa
    var cmdLegacy = new InventoryCountingService.RecordCompoundCommand(
        "SKU-1", "GERAL", 10, 1, 0, 0, eventId, "MANUAL", "PWA", Instant.now(), null);
    assertEquals(10, cmdLegacy.total());
    assertEquals(null, cmdLegacy.boa());
  }

  // Helper
  private static InventoryCountingService.OccurrenceRecord InventoryCountingRecordMock(
      long id, long roundId, long itemId, String sku, int qtd, String loc, String cat, String act,
      String op, UUID eventId, String ori, String dev, Instant cTs, Instant sTs, Long refId) {
    return new InventoryCountingService.OccurrenceRecord(id, roundId, itemId, sku, qtd, loc, cat, act, op, eventId, ori, dev, cTs, sTs, refId);
  }
}
