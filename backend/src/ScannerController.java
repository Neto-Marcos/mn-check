package br.com.mncheck;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scanner")
public class ScannerController {
  private final BarcodeValidationService validationService;
  private final PostgresDatabase database;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public ScannerController(
      BarcodeValidationService validationService,
      ObjectMapper objectMapper
  ) {
    this.validationService = validationService;
    this.objectMapper = objectMapper;
    this.database = new PostgresDatabase(System.getenv("DATABASE_URL"));
  }

  @PostMapping("/validate")
  public ResponseEntity<Map<String, Object>> validate(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody ScanRequest request
  ) throws Exception {
    ExpectedItem expectedItem = loadExpectedItem(authorization, request.mapId());
    BarcodeValidationService.ValidationResult validation;
    try {
      validation = validationService.validate(expectedItem.sku(), request.scannedCode());
    } catch (BarcodeParser.InvalidBarcodeException error) {
      String expected = BarcodeParser.parse(expectedItem.sku()).normalized();
      String scanned = request.scannedCode() == null ? "" : request.scannedCode().trim();
      PostgresDatabase.ScanHistoryEntry saved = database.saveScanHistory(
          request.mapId(),
          request.operator(),
          expected,
          scanned.isBlank() ? "inválido" : scanned.substring(0, Math.min(scanned.length(), 16)),
          false,
          error.getMessage(),
          normalizeSource(request.source())
      );
      return ResponseEntity.ok(Map.of(
          "id", saved.id(),
          "status", "BLOQUEADO",
          "approved", false,
          "reason", error.getMessage(),
          "expected", expected,
          "scanned", saved.scannedCode(),
          "source", saved.source(),
          "at", saved.createdAt().toString()
      ));
    }

    Map<String, Object> legacyResult = Map.of();
    if (validation.approved()) {
      legacyResult = registerApprovedScan(authorization, request.mapId(), validation.expected().digits());
    }

    PostgresDatabase.ScanHistoryEntry saved = database.saveScanHistory(
        request.mapId(),
        request.operator(),
        validation.expected().normalized(),
        validation.scanned().normalized(),
        validation.approved(),
        validation.reason(),
        normalizeSource(request.source())
    );

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", saved.id());
    response.put("status", validation.status());
    response.put("approved", validation.approved());
    response.put("reason", validation.reason());
    response.put("expected", validation.expected().normalized());
    response.put("scanned", validation.scanned().normalized());
    response.put("expectedVoltage", validation.expected().voltageType().label());
    response.put("scannedVoltage", validation.scanned().voltageType().label());
    response.put("source", saved.source());
    response.put("at", saved.createdAt().toString());
    response.putAll(legacyResult);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/history")
  public Map<String, Object> history(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam String mapId,
      @RequestParam(defaultValue = "30") int limit
  ) throws Exception {
    loadBootstrap(authorization);
    int safeLimit = Math.max(1, Math.min(limit, 100));
    List<Map<String, Object>> entries = database.loadScanHistory(mapId, safeLimit).stream()
        .map(item -> Map.<String, Object>of(
            "id", item.id(),
            "mapId", item.mapId(),
            "operator", item.operator(),
            "expected", item.expectedCode(),
            "scanned", item.scannedCode(),
            "approved", item.approved(),
            "status", item.approved() ? "APROVADO" : "BLOQUEADO",
            "reason", item.reason(),
            "source", item.source(),
            "at", item.createdAt().toString()
        ))
        .toList();
    return Map.of("history", entries);
  }

  private Map<String, Object> registerApprovedScan(
      String authorization,
      String mapId,
      String digits
  ) throws Exception {
    String payload = "{\"code\":\"" + digits + "\"}";
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + MnCheckApplication.LEGACY_PORT
            + "/api/maps/" + mapId + "/scan"))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json");
    if (authorization != null && !authorization.isBlank()) {
      builder.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    HttpResponse<String> response = httpClient.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    );
    Map<String, Object> body = objectMapper.readValue(
        response.body(),
        new TypeReference<Map<String, Object>>() {}
    );
    if (response.statusCode() >= 400) {
      throw new ScannerApiException(response.statusCode(), String.valueOf(
          body.getOrDefault("error", "A conferência não pôde ser atualizada.")
      ));
    }
    return body;
  }

  private ExpectedItem loadExpectedItem(String authorization, String mapId) throws Exception {
    Map<String, Object> bootstrap = loadBootstrap(authorization);
    List<Map<String, Object>> maps = objectMapper.convertValue(
        bootstrap.getOrDefault("maps", List.of()),
        new TypeReference<List<Map<String, Object>>>() {}
    );
    Map<String, Object> map = maps.stream()
        .filter(item -> mapId != null && mapId.equals(String.valueOf(item.get("id"))))
        .findFirst()
        .orElseThrow(() -> new ScannerApiException(404, "Mapa não encontrado para este usuário."));
    List<Map<String, Object>> items = objectMapper.convertValue(
        map.getOrDefault("items", List.of()),
        new TypeReference<List<Map<String, Object>>>() {}
    );
    Map<String, Object> pending = items.stream()
        .filter(item -> integer(item.get("checkedQuantity")) < integer(item.get("quantity")))
        .findFirst()
        .orElseThrow(() -> new ScannerApiException(409, "Todos os itens deste mapa já foram conferidos."));
    return new ExpectedItem(
        String.valueOf(pending.getOrDefault("sku", "")),
        String.valueOf(pending.getOrDefault("name", "Produto"))
    );
  }

  private Map<String, Object> loadBootstrap(String authorization) throws Exception {
    if (authorization == null || authorization.isBlank()) {
      throw new ScannerApiException(401, "Sessão expirada. Faça login novamente.");
    }
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + MnCheckApplication.LEGACY_PORT + "/api/bootstrap"))
        .timeout(Duration.ofSeconds(10))
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(
        request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    );
    Map<String, Object> body = objectMapper.readValue(
        response.body(),
        new TypeReference<Map<String, Object>>() {}
    );
    if (response.statusCode() >= 400) {
      throw new ScannerApiException(response.statusCode(), String.valueOf(
          body.getOrDefault("error", "Sessão inválida.")
      ));
    }
    return body;
  }

  private int integer(Object value) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (RuntimeException error) {
      return 0;
    }
  }

  private String normalizeSource(String source) {
    return switch (source == null ? "" : source.toLowerCase()) {
      case "scanner" -> "scanner";
      default -> "manual";
    };
  }

  @ExceptionHandler(BarcodeParser.InvalidBarcodeException.class)
  ResponseEntity<Map<String, Object>> invalidCode(RuntimeException error) {
    return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(ScannerApiException.class)
  ResponseEntity<Map<String, Object>> scannerApiError(ScannerApiException error) {
    return ResponseEntity.status(error.status).body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(PostgresDatabase.DatabaseException.class)
  ResponseEntity<Map<String, Object>> databaseError(PostgresDatabase.DatabaseException error) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("error", "Histórico do scanner temporariamente indisponível."));
  }

  public record ScanRequest(
      String mapId,
      String expectedCode,
      String scannedCode,
      String operator,
      String source
  ) {}

  private record ExpectedItem(String sku, String name) {}

  private static final class ScannerApiException extends RuntimeException {
    final int status;

    ScannerApiException(int status, String message) {
      super(message);
      this.status = status;
    }
  }
}
