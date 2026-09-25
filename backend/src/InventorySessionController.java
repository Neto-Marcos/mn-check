package br.com.mncheck;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/inventarios", "/api/inventory/sessions"})
public class InventorySessionController {
  private final InventorySessionService service;
  private final InventoryCountingService countingService;
  private final InventoryInvestigationService investigationService;
  private final InventoryClosingService closingService;
  private final InventoryReportService reportService;
  private final LegacyAuthenticationClient authentication;

  public InventorySessionController(
      InventorySessionService service,
      InventoryCountingService countingService,
      InventoryInvestigationService investigationService,
      InventoryClosingService closingService,
      InventoryReportService reportService,
      LegacyAuthenticationClient authentication
  ) {
    this.service = service;
    this.countingService = countingService;
    this.investigationService = investigationService != null ? investigationService : new InventoryInvestigationService();
    this.closingService = closingService != null ? closingService : new InventoryClosingService();
    this.reportService = reportService != null ? reportService : new InventoryReportService();
    this.authentication = authentication;
  }

  @PostMapping
  public ResponseEntity<InventorySessionService.InventoryDetail> create(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody CreateRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    InventorySessionService.InventoryDetail created = service.create(
        new InventorySessionService.CreateCommand(request.importacaoSaldoId(), request.branchCode(),
            request.nome(), request.tipo(), request.modo(), request.skus()), user.name());
    return ResponseEntity.status(201).body(created);
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam String branchCode,
      @RequestParam(value = "filtro", required = false) String filtro,
      @RequestParam(value = "status", required = false) String status
  ) {
    authentication.requireInventoryUser(authorization);
    String statusFilter = filtro != null && !filtro.isBlank() ? filtro : status;
    return Map.of("branchCode", branchCode, "inventarios", service.list(branchCode, statusFilter));
  }

  @GetMapping("/{id}")
  public InventorySessionService.InventoryDetail detail(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return service.loadDetail(id, branchCode);
  }

  @PostMapping("/{id}/abrir")
  public InventorySessionService.InventoryDetail open(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestBody TransitionRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return service.open(id, request.branchCode(), request.expectedVersion(), user.name());
  }

  @PostMapping("/{id}/iniciar")
  public InventorySessionService.InventoryDetail start(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestBody TransitionRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return service.start(id, request.branchCode(), request.expectedVersion(), user.name());
  }

  @PostMapping("/{id}/cancelar")
  public InventorySessionService.InventoryDetail cancel(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestBody TransitionRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return service.cancel(id, request.branchCode(), request.expectedVersion(), user.name());
  }

  @GetMapping("/{id}/rodada-ativa")
  public InventoryCountingService.RoundDetail activeRound(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return countingService.getActiveRound(id, branchCode);
  }

  @GetMapping("/{id}/itens")
  public InventoryCountingService.ItemListResult items(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String estado
  ) {
    authentication.requireInventoryUser(authorization);
    return countingService.listItems(id, branchCode, search, estado);
  }

  @PostMapping("/{id}/rodadas/{rodadaId}/contagens")
  public InventoryCountingService.OccurrenceResult recordOccurrence(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable long rodadaId,
      @RequestParam String branchCode,
      @RequestBody RecordOccurrenceRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return countingService.recordOccurrence(id, rodadaId, branchCode, request.toCommand(), user.name());
  }

  @PostMapping("/{id}/rodadas/{rodadaId}/contagem-composta")
  public InventoryCountingService.OccurrenceResult recordCompoundCount(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable long rodadaId,
      @RequestParam String branchCode,
      @RequestBody RecordCompoundRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return countingService.recordCompoundOccurrence(id, rodadaId, branchCode, request.toCommand(), user.name());
  }

  @PostMapping("/{id}/rodadas/{rodadaId}/encerrar")
  public InventoryCountingService.RoundAuditResult closeRound(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable long rodadaId,
      @RequestParam String branchCode,
      @RequestBody(required = false) CloseRoundRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    boolean force = request != null && Boolean.TRUE.equals(request.forcar());
    return countingService.closeRound(id, rodadaId, branchCode, force, user.name());
  }

  @GetMapping("/{id}/rodadas/{rodadaId}/apuracao")
  public InventoryCountingService.RoundAuditResult audit(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable long rodadaId,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return countingService.getRoundAudit(id, rodadaId, branchCode);
  }

  @PostMapping("/{id}/criar-recontagem")
  public InventoryCountingService.RoundDetail createRecount(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode,
      @RequestBody CreateRecountRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return countingService.createRecountRound(id, branchCode, request.itemIds(), user.name());
  }

  // --- Investigações de Divergência (Dia 6) ---

  @GetMapping({"{id}/investigacoes", "{id}/investigations"})
  public List<InventoryInvestigationService.InvestigationSummary> listInvestigations(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode,
      @RequestParam(required = false) String status
  ) {
    authentication.requireInventoryUser(authorization);
    return investigationService.list(id, branchCode, status);
  }

  @GetMapping({"{id}/investigacoes/{investigationId}", "{id}/investigations/{investigationId}"})
  public InventoryInvestigationService.InvestigationDetail getInvestigation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return investigationService.getDetail(id, investigationId, branchCode);
  }

  @PostMapping({"{id}/investigacoes", "{id}/investigations"})
  public ResponseEntity<InventoryInvestigationService.InvestigationDetail> createInvestigation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode,
      @RequestBody CreateInvestigationRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    InventoryInvestigationService.InvestigationDetail created = investigationService.create(
        id, branchCode, new InventoryInvestigationService.CreateCommand(
            request.apuracaoId(), request.causaSuspeita(), request.justificativa()
        ), user.name());
    return ResponseEntity.status(201).body(created);
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/iniciar", "{id}/investigations/{investigationId}/start"})
  public InventoryInvestigationService.InvestigationDetail startInvestigation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody(required = false) StartInvestigationRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    InventoryInvestigationService.StartCommand cmd = request != null
        ? new InventoryInvestigationService.StartCommand(
            request.responsavelId(), request.responsavelNome(), request.causaSuspeita(), request.justificativa())
        : new InventoryInvestigationService.StartCommand(user.name(), user.name(), null, null);
    return investigationService.start(id, investigationId, branchCode, cmd, user.name());
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/suspeita", "{id}/investigations/{investigationId}/suspect-cause"})
  public InventoryInvestigationService.InvestigationDetail updateSuspectCause(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody SuspectCauseRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return investigationService.updateSuspectCause(
        id, investigationId, branchCode,
        new InventoryInvestigationService.SuspectCauseCommand(request.causaSuspeita(), request.justificativa()),
        user.name());
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/status", "{id}/investigations/{investigationId}/status"})
  public InventoryInvestigationService.InvestigationDetail changeStatus(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody ChangeStatusRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return investigationService.changeStatus(id, investigationId, branchCode, request.status(), request.motivo(), user.name());
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/evidencias", "{id}/investigations/{investigationId}/evidence"})
  public ResponseEntity<InventoryInvestigationService.EvidenceRecord> addEvidence(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody AddEvidenceRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    InventoryInvestigationService.EvidenceRecord record = investigationService.addEvidence(
        id, investigationId, branchCode,
        new InventoryInvestigationService.AddEvidenceCommand(request.tipo(), request.descricao(), request.referencia()),
        user.name());
    return ResponseEntity.status(201).body(record);
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/vinculos", "{id}/investigations/{investigationId}/links"})
  public ResponseEntity<InventoryInvestigationService.LinkRecord> addLink(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody AddLinkRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    InventoryInvestigationService.LinkRecord record = investigationService.addLink(
        id, investigationId, branchCode,
        new InventoryInvestigationService.AddLinkCommand(
            request.inventarioItemRelacionadoId(), request.apuracaoRelacionadaId(), request.tipoVinculo(), request.observacao()),
        user.name());
    return ResponseEntity.status(201).body(record);
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/resolver", "{id}/investigations/{investigationId}/resolve"})
  public InventoryInvestigationService.InvestigationDetail resolve(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody ResolveRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return investigationService.resolve(
        id, investigationId, branchCode,
        new InventoryInvestigationService.ResolveCommand(request.causaConfirmada(), request.conclusao()),
        user.name());
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/sem-causa", "{id}/investigations/{investigationId}/unresolved"})
  public InventoryInvestigationService.InvestigationDetail closeUnresolved(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody UnresolvedRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return investigationService.closeUnresolved(
        id, investigationId, branchCode,
        new InventoryInvestigationService.UnresolvedCommand(request.conclusao()),
        user.name());
  }

  @PostMapping({"{id}/investigacoes/{investigationId}/reabrir", "{id}/investigations/{investigationId}/reopen"})
  public InventoryInvestigationService.InvestigationDetail reopen(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @PathVariable UUID investigationId,
      @RequestParam String branchCode,
      @RequestBody ReopenRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    return investigationService.reopen(
        id, investigationId, branchCode,
        new InventoryInvestigationService.ReopenCommand(request.justificativa()),
        user.name());
  }

  @GetMapping("/{id}/fechamento/validacao")
  public InventoryClosingService.ValidationResult validateClosing(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return closingService.validateClosing(id, branchCode);
  }

  @PostMapping("/{id}/fechar")
  public InventoryClosingService.CloseResponse close(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam(required = false) String branchCode,
      @RequestBody CloseInventoryRequest request
  ) {
    LegacyAuthenticationClient.AuthenticatedUser user = authentication.requireInventoryUser(authorization);
    String effectiveBranch = branchCode != null && !branchCode.isBlank() ? branchCode : request.branchCode();
    String effectiveType = request.tipoFechamento() != null && !request.tipoFechamento().isBlank()
        ? request.tipoFechamento()
        : request.tipo();
    InventoryClosingService.ClosingResult result = closingService.closeInventory(
        id, effectiveBranch,
        new InventoryClosingService.CloseCommand(effectiveType, request.justificativa()),
        user
    );
    return new InventoryClosingService.CloseResponse(
        result.id(),
        result.inventarioId(),
        result.inventarioStatus(),
        result.fechadoEm() != null ? result.fechadoEm() : Instant.now(),
        result.tipoFechamento(),
        true
    );
  }

  @GetMapping("/{id}/resultado")
  public InventoryClosingService.ClosingResult getResult(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return closingService.getResult(id, branchCode);
  }

  @GetMapping({"/{id}/detalhe-historico", "/{id}/historico-detalhado"})
  public InventoryClosingService.DetailedHistoryResult getDetailedHistory(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return closingService.getDetailedHistory(id, branchCode);
  }

  @GetMapping("/{id}/relatorio")
  public InventoryReportService.ReportProjection report(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode,
      @RequestParam(defaultValue = "R1") String contexto,
      @RequestParam(defaultValue = "TODOS") String status,
      @RequestParam(defaultValue = "TODAS") String localizacao,
      @RequestParam(defaultValue = "TODAS") String condicao,
      @RequestParam(defaultValue = "TODAS") String investigacao,
      @RequestParam(defaultValue = "") String busca,
      @RequestParam(defaultValue = "SKU") String ordenarPor
  ) {
    authentication.requireInventoryUser(authorization);
    return reportService.project(id, branchCode,
        new InventoryReportService.ReportFilter(contexto, status, localizacao, condicao, investigacao, busca, ordenarPor));
  }

  @GetMapping("/{id}/relatorio.xlsx")
  public ResponseEntity<byte[]> reportXlsx(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable long id,
      @RequestParam String branchCode,
      @RequestParam(defaultValue = "R1") String contexto,
      @RequestParam(defaultValue = "TODOS") String status,
      @RequestParam(defaultValue = "TODAS") String localizacao,
      @RequestParam(defaultValue = "TODAS") String condicao,
      @RequestParam(defaultValue = "TODAS") String investigacao,
      @RequestParam(defaultValue = "") String busca,
      @RequestParam(defaultValue = "SKU") String ordenarPor
  ) {
    authentication.requireInventoryUser(authorization);
    InventoryReportService.ReportProjection projection = reportService.project(id, branchCode,
        new InventoryReportService.ReportFilter(contexto, status, localizacao, condicao, investigacao, busca, ordenarPor));
    byte[] workbook = reportService.exportXlsx(projection);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(InventoryReportService.XLSX_CONTENT_TYPE))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventario-" + id + "-" + contexto.toLowerCase() + ".xlsx")
        .body(workbook);
  }

  @ExceptionHandler(InventoryClosingService.ClosingException.class)
  ResponseEntity<Map<String, Object>> closingError(InventoryClosingService.ClosingException error) {
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(InventoryReportService.ReportException.class)
  ResponseEntity<Map<String, Object>> reportError(InventoryReportService.ReportException error) {
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(InventoryInvestigationService.InvestigationException.class)
  ResponseEntity<Map<String, Object>> investigationError(InventoryInvestigationService.InvestigationException error) {
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(InventorySessionService.InventoryException.class)
  ResponseEntity<Map<String, Object>> inventoryError(InventorySessionService.InventoryException error) {
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(InventoryCountingService.UncountedItemsConflictException.class)
  ResponseEntity<Map<String, Object>> uncountedError(InventoryCountingService.UncountedItemsConflictException error) {
    return ResponseEntity.status(409).body(Map.of(
        "error", error.getMessage(),
        "pendentes", error.pendentes(),
        "bloqueado", true
    ));
  }

  @ExceptionHandler(InventoryCountingService.CountingException.class)
  ResponseEntity<Map<String, Object>> countingError(InventoryCountingService.CountingException error) {
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(LegacyAuthenticationClient.AuthenticationException.class)
  ResponseEntity<Map<String, Object>> authenticationError(
      LegacyAuthenticationClient.AuthenticationException error) {
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  public record CreateRequest(long importacaoSaldoId, String branchCode, String nome, String tipo,
                              String modo, List<String> skus) {}
  public record TransitionRequest(String branchCode, long expectedVersion) {}
  public record CloseRoundRequest(Boolean forcar) {}
  public record CreateRecountRequest(List<Long> itemIds) {}
  public record CloseInventoryRequest(String tipo, String tipoFechamento, String justificativa, String branchCode) {}

  public record CreateInvestigationRequest(long apuracaoId, String causaSuspeita, String justificativa) {}
  public record StartInvestigationRequest(String responsavelId, String responsavelNome, String causaSuspeita, String justificativa) {}
  public record SuspectCauseRequest(String causaSuspeita, String justificativa) {}
  public record ChangeStatusRequest(String status, String motivo) {}
  public record AddEvidenceRequest(String tipo, String descricao, String referencia) {}
  public record AddLinkRequest(long inventarioItemRelacionadoId, Long apuracaoRelacionadaId, String tipoVinculo, String observacao) {}
  public record ResolveRequest(String causaConfirmada, String conclusao) {}
  public record UnresolvedRequest(String conclusao) {}
  public record ReopenRequest(String justificativa) {}

  public record RecordOccurrenceRequest(
      String sku,
      int quantidade,
      String localizacao,
      String categoria,
      String condicao,
      String tipoAcao,
      Object clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Long referenciaId
  ) {
    public InventoryCountingService.RecordOccurrenceCommand toCommand() {
      String cat = condicao != null && !condicao.isBlank() ? condicao : categoria;
      return new InventoryCountingService.RecordOccurrenceCommand(
          sku, quantidade, localizacao, cat, tipoAcao, clientEventId, origem, dispositivo, clientTimestamp, referenciaId);
    }
  }

  public record RecordCompoundRequest(
      String sku,
      String localizacao,
      Integer total,
      Integer boa,
      int avaria,
      int assistencia,
      int outros,
      Object clientEventId,
      String origem,
      String dispositivo,
      Instant clientTimestamp,
      Long referenciaId
  ) {
    public InventoryCountingService.RecordCompoundCommand toCommand() {
      UUID eventId;
      try {
        eventId = clientEventId instanceof UUID u ? u : UUID.fromString(clientEventId.toString());
      } catch (Exception ex) {
        throw new InventoryCountingService.ValidationException("client_event_id inválido ou ausente. Deve ser um UUID.");
      }
      return new InventoryCountingService.RecordCompoundCommand(
          sku, localizacao, total, boa, avaria, assistencia, outros, eventId, origem, dispositivo, clientTimestamp, referenciaId);
    }
  }
}
