package br.com.mncheck;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
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
@RequestMapping("/api/inventarios")
public class InventorySessionController {
  private final InventorySessionService service;
  private final InventoryCountingService countingService;
  private final LegacyAuthenticationClient authentication;

  public InventorySessionController(
      InventorySessionService service,
      InventoryCountingService countingService,
      LegacyAuthenticationClient authentication
  ) {
    this.service = service;
    this.countingService = countingService;
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
      @RequestParam String branchCode
  ) {
    authentication.requireInventoryUser(authorization);
    return Map.of("branchCode", branchCode, "inventarios", service.list(branchCode));
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
}
