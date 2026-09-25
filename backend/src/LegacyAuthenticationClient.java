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
  private static final Duration CACHE_TTL = Duration.ofSeconds(60);

  /**
   * DÍVIDA TÉCNICA:
   * A dependência de chamar o endpoint '/api/bootstrap' do servidor legado para validar credenciais
   * a cada requisição gera overhead de rede e serialização de todo o payload inicial.
   * O cache em memória mitiga esse gargalo durante a operação contínua.
   * Futuramente, substituir por um endpoint mínimo de identidade/sessão ('/api/auth/verify').
   */
  private final Map<String, CachedUser> authCache = new java.util.concurrent.ConcurrentHashMap<>();
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public LegacyAuthenticationClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AuthenticatedUser requireInventoryUser(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      throw new AuthenticationException(401, "Sessão expirada. Faça login novamente.");
    }

    java.time.Instant now = java.time.Instant.now();
    CachedUser cached = authCache.get(authorization);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.user();
    }

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + MnCheckApplication.LEGACY_PORT + "/api/bootstrap"))
          .timeout(Duration.ofSeconds(30))
          .header(HttpHeaders.AUTHORIZATION, authorization)
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());
      Map<String, Object> payload = objectMapper.readValue(response.body(),
          new TypeReference<Map<String, Object>>() {});
      if (response.statusCode() >= 400) {
        authCache.remove(authorization);
        throw new AuthenticationException(response.statusCode(),
            String.valueOf(payload.getOrDefault("error", "Sessão inválida.")));
      }
      Map<String, Object> user = objectMapper.convertValue(payload.get("user"),
          new TypeReference<Map<String, Object>>() {});
      String role = String.valueOf(user.getOrDefault("role", ""));
      if (!INVENTORY_ROLES.contains(role)) {
        authCache.remove(authorization);
        throw new AuthenticationException(403, "Ação permitida apenas para o estoque e administradores.");
      }
      String name = String.valueOf(user.getOrDefault("name", "")).trim();
      if (name.isBlank()) name = String.valueOf(user.getOrDefault("username", "Sistema"));
      AuthenticatedUser authenticated = new AuthenticatedUser(String.valueOf(user.getOrDefault("id", "")), name, role);

      // Armazena no cache apenas os dados estritamente necessários com TTL de 60s
      authCache.put(authorization, new CachedUser(authenticated, now.plus(CACHE_TTL)));
      return authenticated;
    } catch (AuthenticationException error) {
      throw error;
    } catch (Exception error) {
      throw new AuthenticationException(503, "Não foi possível validar a sessão atual.");
    }
  }

  public void clearCache() {
    authCache.clear();
  }

  public int cacheSize() {
    return authCache.size();
  }

  public record AuthenticatedUser(String id, String name, String role) {}
  record CachedUser(AuthenticatedUser user, java.time.Instant expiresAt) {}

  public static final class AuthenticationException extends RuntimeException {
    private final int status;
    AuthenticationException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }
}
