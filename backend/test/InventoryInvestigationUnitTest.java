package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InventoryInvestigationUnitTest {

  @Test
  void testConstantsAndEnumSets() {
    assertTrue(InventoryInvestigationService.STATUSES.contains("PENDENTE"));
    assertTrue(InventoryInvestigationService.STATUSES.contains("EM_INVESTIGACAO"));
    assertTrue(InventoryInvestigationService.STATUSES.contains("AGUARDANDO_EVIDENCIA"));
    assertTrue(InventoryInvestigationService.STATUSES.contains("RESOLVIDA"));
    assertTrue(InventoryInvestigationService.STATUSES.contains("SEM_CAUSA_IDENTIFICADA"));
    assertEquals(5, InventoryInvestigationService.STATUSES.size());

    assertTrue(InventoryInvestigationService.CAUSES.contains("ERRO_CONTAGEM"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("INVERSAO_PRODUTO"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("INVERSAO_VOLTAGEM"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("AVARIA"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("ASSISTENCIA"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("ERRO_SEPARACAO"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("MERCADORIA_CLIENTE"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("MOVIMENTACAO"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("FISCAL_NF"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("NAO_LOCALIZADO"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("OUTRO"));
    assertTrue(InventoryInvestigationService.CAUSES.contains("NAO_IDENTIFICADA"));
    assertEquals(12, InventoryInvestigationService.CAUSES.size());

    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("OBSERVACAO"));
    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("FOTO"));
    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("DOCUMENTO"));
    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("NOTA_FISCAL"));
    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("CONTAGEM"));
    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("PRODUTO_RELACIONADO"));
    assertTrue(InventoryInvestigationService.EVIDENCE_TYPES.contains("OUTRO"));
    assertEquals(7, InventoryInvestigationService.EVIDENCE_TYPES.size());

    assertTrue(InventoryInvestigationService.LINK_TYPES.contains("POSSIVEL_INVERSAO"));
    assertTrue(InventoryInvestigationService.LINK_TYPES.contains("POSSIVEL_VOLTAGEM"));
    assertTrue(InventoryInvestigationService.LINK_TYPES.contains("MESMO_PRODUTO"));
    assertTrue(InventoryInvestigationService.LINK_TYPES.contains("MOVIMENTACAO_RELACIONADA"));
    assertTrue(InventoryInvestigationService.LINK_TYPES.contains("OUTRO"));
    assertEquals(5, InventoryInvestigationService.LINK_TYPES.size());

    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("CRIADA"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("INICIADA"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("STATUS_ALTERADO"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("CAUSA_SUSPEITA_ALTERADA"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("EVIDENCIA_ADICIONADA"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("PRODUTO_RELACIONADO"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("RESOLVIDA"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("ENCERRADA_SEM_CAUSA"));
    assertTrue(InventoryInvestigationService.EVENT_TYPES.contains("REABERTA"));
    assertEquals(9, InventoryInvestigationService.EVENT_TYPES.size());
  }

  @Test
  void testInvestigationExceptionsHierarchy() {
    var valEx = new InventoryInvestigationService.ValidationException("Erro de validação");
    assertEquals(400, valEx.status());
    assertEquals("Erro de validação", valEx.getMessage());

    var notFoundEx = new InventoryInvestigationService.NotFoundException("Não encontrado");
    assertEquals(404, notFoundEx.status());
    assertEquals("Não encontrado", notFoundEx.getMessage());

    var conflictEx = new InventoryInvestigationService.ConflictException("Conflito");
    assertEquals(409, conflictEx.status());
    assertEquals("Conflito", conflictEx.getMessage());

    var dbEx = new InventoryInvestigationService.DatabaseException("Erro no banco", new RuntimeException("causa"));
    assertEquals(503, dbEx.status());
    assertEquals("Erro no banco", dbEx.getMessage());
    assertNotNull(dbEx.getCause());
  }

  @Test
  void testInvestigationDetailAndDTOs() {
    UUID invId = UUID.randomUUID();
    Instant now = Instant.now();

    var apuracaoCtx = new InventoryInvestigationService.ApuracaoContext(
        10L, 2L, 1, "CONTAGEM", 20, true, 18, -2, "DIVERGENTE", Map.of("VENDAS", Map.of("BOA", 18))
    );
    var evidence = new InventoryInvestigationService.EvidenceRecord(
        1L, invId, "NOTA_FISCAL", "NF 12345", "http://nf", now, "Operador"
    );
    var link = new InventoryInvestigationService.LinkRecord(
        2L, invId, 15L, "SKU-REL", 11L, "POSSIVEL_INVERSAO", "Inversão com SKU-B", now, "Operador"
    );
    var event = new InventoryInvestigationService.EventRecord(
        3L, invId, "CRIADA", Map.of("motivo", "Divergência"), now, "Operador"
    );

    var detail = new InventoryInvestigationService.InvestigationDetail(
        invId, 1L, 100L, 10L, "SKU-TEST", "Produto Teste", "EM_INVESTIGACAO",
        "INVERSAO_PRODUTO", null, "Suspeita de inversão", null,
        "supervisor", "Supervisor Geral", now, "criador", now, "atualizador",
        null, null, 1L, apuracaoCtx, List.of(evidence), List.of(link), List.of(event)
    );

    assertEquals(invId, detail.id());
    assertEquals("SKU-TEST", detail.sku());
    assertEquals("Produto Teste", detail.descricaoSnapshot());
    assertEquals("EM_INVESTIGACAO", detail.status());
    assertEquals("INVERSAO_PRODUTO", detail.causaSuspeita());
    assertNull(detail.causaConfirmada());
    assertEquals(1, detail.evidencias().size());
    assertEquals("NOTA_FISCAL", detail.evidencias().get(0).tipo());
    assertEquals(1, detail.vinculos().size());
    assertEquals("POSSIVEL_INVERSAO", detail.vinculos().get(0).tipoVinculo());
    assertEquals(1, detail.eventos().size());
    assertEquals("CRIADA", detail.eventos().get(0).tipoEvento());
    assertEquals(-2, detail.apuracao().diferenca());
  }
}
