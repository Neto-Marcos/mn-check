package br.com.mncheck;

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
  private final LegacyAuthenticationClient authentication;

  public InventorySessionController(InventorySessionService service,
      LegacyAuthenticationClient authentication) {
    this.service = service;
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

  @ExceptionHandler(InventorySessionService.InventoryException.class)
  ResponseEntity<Map<String, Object>> inventoryError(InventorySessionService.InventoryException error) {
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
}
