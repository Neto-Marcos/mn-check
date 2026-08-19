package br.com.mncheck;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static br.com.mncheck.EnterpriseDatabase.EnterpriseException;

@RestController
@RequestMapping("/api/v2")
public class EnterpriseController {
  private final EnterpriseService service;

  public EnterpriseController() {
    this.service = new EnterpriseService(new EnterpriseDatabase(System.getenv("DATABASE_URL")));
  }

  @GetMapping("/workspace")
  public Map<String, Object> workspace(HttpServletRequest request,
                                       @RequestParam(defaultValue = "") String branchId) {
    return service.workspace(principal(request), branchId);
  }

  @GetMapping("/search")
  public Map<String, Object> search(HttpServletRequest request,
                                    @RequestParam(defaultValue = "") String branchId,
                                    @RequestParam String q) {
    return service.search(principal(request), branchId, q);
  }

  @PostMapping("/branches")
  public Map<String, Object> createBranch(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.createBranch(principal(request), body, idempotency(key));
  }

  @PatchMapping("/profiles/branch")
  public Map<String, Object> assignBranch(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.assignBranch(principal(request), body, idempotency(key));
  }

  @PostMapping("/products")
  public Map<String, Object> saveProduct(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.saveProduct(principal(request), body, idempotency(key));
  }

  @PostMapping("/receipts")
  public Map<String, Object> createReceipt(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.createReceipt(principal(request), body, idempotency(key));
  }

  @GetMapping("/receipts/{id}")
  public Map<String, Object> receipt(HttpServletRequest request, @PathVariable UUID id) {
    return service.receiptDetails(principal(request), id);
  }

  @PostMapping("/receipts/{id}/nfe")
  public Map<String, Object> importNfe(HttpServletRequest request, @PathVariable UUID id,
                                       @RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.importNfe(principal(request), id, body, idempotency(key));
  }

  @PostMapping("/receipts/{id}/scan")
  public Map<String, Object> scanReceipt(HttpServletRequest request, @PathVariable UUID id,
                                         @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.scanReceipt(principal(request), id, body, idempotency(key));
  }

  @PostMapping("/receipts/{id}/damage")
  public Map<String, Object> registerDamage(HttpServletRequest request, @PathVariable UUID id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.registerDamage(principal(request), id, body, idempotency(key));
  }

  @PostMapping("/receipts/{id}/finalize")
  public Map<String, Object> finalizeReceipt(HttpServletRequest request, @PathVariable UUID id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.finalizeReceipt(principal(request), id, idempotency(key));
  }

  @PostMapping("/maps")
  public Map<String, Object> createMap(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.createMap(principal(request), body, idempotency(key));
  }

  @GetMapping("/maps/{id}")
  public Map<String, Object> map(HttpServletRequest request, @PathVariable UUID id) {
    return service.mapDetails(principal(request), id);
  }

  @PostMapping("/maps/{id}/publish")
  public Map<String, Object> publishMap(HttpServletRequest request, @PathVariable UUID id,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.publishMap(principal(request), id, idempotency(key));
  }

  @PostMapping("/maps/{id}/scan-picking")
  public Map<String, Object> scanPicking(HttpServletRequest request, @PathVariable UUID id,
                                         @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.scanMap(principal(request), id, body, false, idempotency(key));
  }

  @PostMapping("/maps/{id}/finish-picking")
  public Map<String, Object> finishPicking(HttpServletRequest request, @PathVariable UUID id,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.finishPicking(principal(request), id, idempotency(key));
  }

  @PostMapping("/maps/{id}/authorize-shortage")
  public Map<String, Object> authorizeMapShortage(HttpServletRequest request, @PathVariable UUID id,
                                                   @RequestBody Map<String, Object> body,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.authorizeMapShortage(principal(request), id, body, idempotency(key));
  }

  @PostMapping("/maps/{id}/scan-conference")
  public Map<String, Object> scanConference(HttpServletRequest request, @PathVariable UUID id,
                                            @RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.scanMap(principal(request), id, body, true, idempotency(key));
  }

  @PostMapping("/maps/{id}/dispatch")
  public Map<String, Object> dispatch(HttpServletRequest request, @PathVariable UUID id,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.dispatchMap(principal(request), id, idempotency(key));
  }

  @PostMapping("/maps/{id}/cancel")
  public Map<String, Object> cancelMap(HttpServletRequest request, @PathVariable UUID id,
                                       @RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.cancelMap(principal(request), id, String.valueOf(body.getOrDefault("reason", "")), idempotency(key));
  }

  @PostMapping("/transfers")
  public Map<String, Object> createTransfer(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.createTransfer(principal(request), body, idempotency(key));
  }

  @GetMapping("/transfers/{id}")
  public Map<String, Object> transfer(HttpServletRequest request, @PathVariable UUID id) {
    return service.transferDetails(principal(request), id);
  }

  @PostMapping("/transfers/{id}/approve")
  public Map<String, Object> approveTransfer(HttpServletRequest request, @PathVariable UUID id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.approveTransfer(principal(request), id, idempotency(key));
  }

  @PostMapping("/transfers/{id}/ship")
  public Map<String, Object> shipTransfer(HttpServletRequest request, @PathVariable UUID id,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.shipTransfer(principal(request), id, idempotency(key));
  }

  @PostMapping("/transfers/{id}/cancel")
  public Map<String, Object> cancelTransfer(HttpServletRequest request, @PathVariable UUID id,
                                            @RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.cancelTransfer(principal(request), id, String.valueOf(body.getOrDefault("reason", "")), idempotency(key));
  }

  @PostMapping("/transfers/{id}/receive")
  public Map<String, Object> receiveTransfer(HttpServletRequest request, @PathVariable UUID id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.receiveTransfer(principal(request), id, body, idempotency(key));
  }

  @PostMapping("/exceptions/{id}/resolve")
  public Map<String, Object> resolveException(HttpServletRequest request, @PathVariable UUID id,
                                              @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.resolveException(principal(request), id, body, idempotency(key));
  }

  @PostMapping("/print-jobs")
  public Map<String, Object> printJob(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.createPrintJob(principal(request), body, idempotency(key));
  }

  @PostMapping("/printers")
  public Map<String, Object> createPrinter(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.createPrinter(principal(request), body, idempotency(key));
  }

  @PatchMapping("/parameters/{parameterKey}")
  public Map<String, Object> saveParameter(HttpServletRequest request, @PathVariable String parameterKey,
                                            @RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.saveParameter(principal(request), parameterKey, body, idempotency(key));
  }

  @PostMapping("/counts/apply")
  public Map<String, Object> applyCount(HttpServletRequest request, @RequestBody Map<String, Object> body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.applyCount(principal(request), body, idempotency(key));
  }

  @PostMapping("/movements/{id}/reverse")
  public Map<String, Object> reverseMovement(HttpServletRequest request, @PathVariable UUID id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.reverseMovement(principal(request), id, String.valueOf(body.getOrDefault("reason", "")), idempotency(key));
  }

  @GetMapping("/inventory/reconcile")
  public Map<String, Object> reconcile(HttpServletRequest request,
                                       @RequestParam(defaultValue = "") String branchId) {
    return service.reconcile(principal(request), branchId);
  }

  @ExceptionHandler(EnterpriseException.class)
  public ResponseEntity<Map<String, Object>> enterpriseError(EnterpriseException error) {
    if (error.status() >= 500) error.printStackTrace();
    return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> invalidArgument(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", "Identificador ou conteúdo inválido."));
  }

  private static MmCheckServer.SessionPrincipal principal(HttpServletRequest request) {
    return MmCheckServer.enterprisePrincipal(request.getHeader("Authorization"));
  }

  private static String idempotency(String key) {
    if (key == null || key.isBlank() || key.length() > 160) {
      throw new EnterpriseException(400, "Envie uma chave Idempotency-Key válida.");
    }
    return key.trim();
  }
}
