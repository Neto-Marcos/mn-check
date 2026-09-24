package br.com.mncheck;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class LegacyAuthenticationClient {
  private static final Set<String> INVENTORY_ROLES = Set.of("admin", "stock");
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public LegacyAuthenticationClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AuthenticatedUser requireInventoryUser(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      throw new AuthenticationException(401, "Sessão expirada. Faça login novamente.");
    }
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + MnCheckApplication.LEGACY_PORT + "/api/bootstrap"))
          .timeout(Duration.ofSeconds(10))
          .header(HttpHeaders.AUTHORIZATION, authorization)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());
      Map<String, Object> payload = objectMapper.readValue(response.body(),
          new TypeReference<Map<String, Object>>() {});
      if (response.statusCode() >= 400) {
        throw new AuthenticationException(response.statusCode(),
            String.valueOf(payload.getOrDefault("error", "Sessão inválida.")));
      }
      Map<String, Object> user = objectMapper.convertValue(payload.get("user"),
          new TypeReference<Map<String, Object>>() {});
      String role = String.valueOf(user.getOrDefault("role", ""));
      if (!INVENTORY_ROLES.contains(role)) {
        throw new AuthenticationException(403, "Ação permitida apenas para o estoque e administradores.");
      }
      String name = String.valueOf(user.getOrDefault("name", "")).trim();
      if (name.isBlank()) name = String.valueOf(user.getOrDefault("username", "Sistema"));
      return new AuthenticatedUser(String.valueOf(user.getOrDefault("id", "")), name, role);
    } catch (AuthenticationException error) {
      throw error;
    } catch (Exception error) {
      throw new AuthenticationException(503, "Não foi possível validar a sessão atual.");
    }
  }

  public record AuthenticatedUser(String id, String name, String role) {}

  public static final class AuthenticationException extends RuntimeException {
    private final int status;
    AuthenticationException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }
}
