package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InventoryBlindModeP0UnitTest {

  @Test
  @DisplayName("InventorySecurityPolicy: R1 de inventário CEGO é protegida enquanto não estiver ENCERRADO")
  void testR1BlindProtectionUntilClosed() {
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "ABERTO", 1));
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "EM_CONTAGEM", 1));
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "EM_APURACAO", 1));
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "EM_RECONTAGEM", 1));
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "EM_INVESTIGACAO", 1));

    // Após ENCERRADO, proteção é liberada para auditoria histórica
    assertFalse(InventorySecurityPolicy.isRoundProtected("CEGO", "ENCERRADO", 1));

    // R1 em modo NORMAL não é cego
    assertFalse(InventorySecurityPolicy.isRoundProtected("NORMAL", "EM_CONTAGEM", 1));
    assertFalse(InventorySecurityPolicy.isRoundProtected("NORMAL", "EM_APURACAO", 1));
  }

  @Test
  @DisplayName("InventorySecurityPolicy: R2 em inventário CEGO permanece SEMPRE protegida até ENCERRADO")
  void testR2BlindProtectionUntilClosed() {
    // Modo CEGO com R2: R2 deve ser cega mesmo após finalizada!
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "EM_RECONTAGEM", 2));
    assertTrue(InventorySecurityPolicy.isRoundProtected("CEGO", "EM_INVESTIGACAO", 2));

    // Somente após ENCERRADO R2 é liberada em modo CEGO
    assertFalse(InventorySecurityPolicy.isRoundProtected("CEGO", "ENCERRADO", 2));

    // Modo NORMAL com R2: apuração é visível (pois inventário é normal)
    assertFalse(InventorySecurityPolicy.isRoundProtected("NORMAL", "EM_INVESTIGACAO", 2));
    assertFalse(InventorySecurityPolicy.isRoundProtected("NORMAL", "ENCERRADO", 2));
  }

  @Test
  @DisplayName("InventorySecurityPolicy: Investigações seguem proteção do inventário")
  void testInvestigationProtection() {
    assertTrue(InventorySecurityPolicy.isInvestigationProtected("CEGO", "EM_INVESTIGACAO", 1));
    assertTrue(InventorySecurityPolicy.isInvestigationProtected("CEGO", "EM_INVESTIGACAO", 2));
    assertFalse(InventorySecurityPolicy.isInvestigationProtected("NORMAL", "EM_INVESTIGACAO", 1));
    assertFalse(InventorySecurityPolicy.isInvestigationProtected("NORMAL", "EM_INVESTIGACAO", 2));
    assertFalse(InventorySecurityPolicy.isInvestigationProtected("CEGO", "ENCERRADO", 1));
  }

  @Test
  @DisplayName("InventorySecurityPolicy: Inventário protegido globalmente enquanto CEGO e não ENCERRADO")
  void testInventoryGlobalProtection() {
    assertTrue(InventorySecurityPolicy.isInventoryProtected("CEGO", "ABERTO"));
    assertTrue(InventorySecurityPolicy.isInventoryProtected("CEGO", "EM_CONTAGEM"));
    assertFalse(InventorySecurityPolicy.isInventoryProtected("NORMAL", "EM_CONTAGEM"));
    assertFalse(InventorySecurityPolicy.isInventoryProtected("CEGO", "ENCERRADO"));
    assertFalse(InventorySecurityPolicy.isInventoryProtected("NORMAL", "ENCERRADO"));
  }

  @Test
  @DisplayName("Report Service Summary: Protege conformes, divergentes, saldo e diferenças contra inferência")
  void testReportSummaryBlindInferenceProtection() {
    // Cenário de inferência:
    // SKU-A: saldo 10, contado 8 -> DIVERGENTE (falta -2)
    // SKU-B: saldo 5, contado 5 -> CONFORME (diff 0)
    InventoryReportService.ReportItem itemA = new InventoryReportService.ReportItem(
        "SKU-A", "Produto A", Map.of("GERAL", Map.of("BOA", 8)), 8, "DIVERGENTE", 10, -2, null, null
    );
    InventoryReportService.ReportItem itemB = new InventoryReportService.ReportItem(
        "SKU-B", "Produto B", Map.of("GERAL", Map.of("BOA", 5)), 5, "CONFORME", 5, 0, null, null
    );

    // Quando protegido:
    InventoryReportService.ReportFilter protectedFilter = InventoryReportService.normalize(
        new InventoryReportService.ReportFilter("R1", "TODOS", "TODAS", "TODAS", "TODAS", "", "SKU")
    );
    List<InventoryReportService.ReportItem> protectedList = InventoryReportService.applyFilters(
        List.of(itemA, itemB), protectedFilter, true
    );

    assertEquals(2, protectedList.size());
    for (InventoryReportService.ReportItem item : protectedList) {
      assertNull(item.saldo(), "Saldo snapshot do item não pode vazar");
      assertNull(item.diferenca(), "Diferença do item não pode vazar");
      assertEquals("CONTADO", item.estado(), "Estado não pode revelar CONFORME ou DIVERGENTE");
    }

    // Projeção do relatório quando protectedFields é true
    InventoryReportService.Summary protectedSummary = summarizeReportItems(protectedList, true);
    assertEquals(2, protectedSummary.total());
    assertEquals(2, protectedSummary.contados());
    assertEquals(0, protectedSummary.naoContados());
    assertNull(protectedSummary.conformes(), "Total de conformes não pode permitir inferência");
    assertNull(protectedSummary.divergentes(), "Total de divergentes não pode permitir inferência");
    assertNull(protectedSummary.saldoTotal(), "Saldo total acumulado não pode vazar");
    assertNull(protectedSummary.diferencaTotal(), "Diferença total acumulada não pode vazar");
    assertEquals(13, protectedSummary.quantidadeTotal()); // Físico contado é legítimo

    // Quando NÃO protegido (ex: após encerramento ou em modo NORMAL):
    InventoryReportService.Summary normalSummary = summarizeReportItems(List.of(itemA, itemB), false);
    assertEquals(2, normalSummary.total());
    assertEquals(1, normalSummary.conformes());
    assertEquals(1, normalSummary.divergentes());
    assertEquals(15, normalSummary.saldoTotal());
    assertEquals(-2, normalSummary.diferencaTotal());
  }

  @Test
  @DisplayName("Counting Service Audit: AuditSummary e AuditItem respeitam proteção")
  void testAuditSummaryAndItemProtection() {
    InventoryCountingService.AuditSummary protectedAuditSummary = new InventoryCountingService.AuditSummary(
        10, null, null, 2, null, null, null, null
    );
    assertEquals(10, protectedAuditSummary.totalItens());
    assertEquals(2, protectedAuditSummary.naoContados());
    assertNull(protectedAuditSummary.conformes());
    assertNull(protectedAuditSummary.divergentes());
    assertNull(protectedAuditSummary.conformesAposRecontagem());
    assertNull(protectedAuditSummary.divergenciasConfirmadas());
    assertNull(protectedAuditSummary.totalFalta());
    assertNull(protectedAuditSummary.totalSobra());

    InventoryCountingService.AuditItem protectedAuditItem = new InventoryCountingService.AuditItem(
        100L, 200L, "SKU-TESTE", "Desc", null, true, 8, null, "CONTADO", Map.of()
    );
    assertNull(protectedAuditItem.saldoSnapshot());
    assertNull(protectedAuditItem.diferenca());
    assertEquals("CONTADO", protectedAuditItem.estado());
  }

  @Test
  @DisplayName("Investigation Service: ApuracaoContext e InvestigationSummary protegem saldo e diferenças")
  void testInvestigationContextProtection() {
    InventoryInvestigationService.ApuracaoContext protectedContext = new InventoryInvestigationService.ApuracaoContext(
        10L, 20L, 2, "RECONTAGEM", null, true, null, null, "CONTADO", Map.of()
    );
    assertNull(protectedContext.saldoSnapshot());
    assertNull(protectedContext.quantidadeFisica());
    assertNull(protectedContext.diferenca());
    assertEquals("CONTADO", protectedContext.estado());

    InventoryInvestigationService.InvestigationSummary protectedSummary = new InventoryInvestigationService.InvestigationSummary(
        UUID.randomUUID(), 1L, 2L, 3L, 4L, "SKU-TESTE", "Desc", "PENDENTE", null, null,
        null, null, null, null, null, null, null, null, 0, 0,
        Instant.now(), "Operador", Instant.now(), "Operador", null, null, 1L
    );
    assertNull(protectedSummary.saldoSnapshot());
    assertNull(protectedSummary.quantidadeFisica());
    assertNull(protectedSummary.diferenca());
    assertNull(protectedSummary.apuracaoEstado());
  }

  @Test
  @DisplayName("Closing Service: CloseResponse contém apenas payload mínimo de confirmação")
  void testCloseResponseStructure() {
    Instant now = Instant.now();
    InventoryClosingService.CloseResponse res = new InventoryClosingService.CloseResponse(
        10L, 1L, "ENCERRADO", now, "NORMAL", true
    );
    assertEquals(10L, res.id());
    assertEquals(1L, res.inventarioId());
    assertEquals("ENCERRADO", res.status());
    assertEquals(now, res.timestamp());
    assertEquals("NORMAL", res.modoFechamento());
    assertTrue(res.confirmacao());
  }

  @Test
  @DisplayName("Closing Service: SummaryMetrics permite nulos em contexto protegido")
  void testSummaryMetricsNullability() {
    InventoryClosingService.SummaryMetrics safeSummary = new InventoryClosingService.SummaryMetrics(
        10, null, null, null, null, null, null
    );
    assertEquals(10, safeSummary.totalItens());
    assertNull(safeSummary.itensConformes());
    assertNull(safeSummary.divergenciasConfirmadas());
    assertNull(safeSummary.investigacoesResolvidas());
    assertNull(safeSummary.investigacoesSemCausa());
    assertNull(safeSummary.investigacoesPendentes());
    assertNull(safeSummary.itensNaoContados());
  }

  private InventoryReportService.Summary summarizeReportItems(List<InventoryReportService.ReportItem> list, boolean protectedFields) {
    int counted = 0, conform = 0, div = 0, pending = 0, balance = 0, quantity = 0, diff = 0;
    for (InventoryReportService.ReportItem i : list) {
      if (i.quantidade() == null) pending++;
      else { counted++; quantity += i.quantidade(); }
      if (i.estado().startsWith("CONFORME")) conform++;
      if (i.estado().contains("DIVERG")) div++;
      if (!protectedFields) {
        balance += i.saldo() == null ? 0 : i.saldo();
        diff += i.diferenca() == null ? 0 : i.diferenca();
      }
    }
    return new InventoryReportService.Summary(
        list.size(), counted, pending,
        protectedFields ? null : conform,
        protectedFields ? null : div,
        protectedFields ? null : balance,
        quantity,
        protectedFields ? null : diff
    );
  }
}
