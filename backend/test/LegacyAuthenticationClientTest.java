package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LegacyAuthenticationClientTest {
  private LegacyAuthenticationClient client;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    client = new LegacyAuthenticationClient(objectMapper);
    client.clearCache();
  }

  @Test
  void rejectsEmptyOrNullToken() {
    assertThrows(LegacyAuthenticationClient.AuthenticationException.class, () ->
        client.requireInventoryUser(null));
    assertThrows(LegacyAuthenticationClient.AuthenticationException.class, () ->
        client.requireInventoryUser("   "));
  }

  @Test
  void cachesAuthenticatedUserAndReusesWithoutCallingLegacyServer() {
    // Simulando inserção manual no cache para validar hit e integridade
    String token = "Bearer token-teste-cache-123";
    LegacyAuthenticationClient.AuthenticatedUser user = new LegacyAuthenticationClient.AuthenticatedUser(
        "1", "Operador Teste", "admin");

    client.clearCache();
    assertEquals(0, client.cacheSize());

    // Usando reflexão ou estrutura interna para injetar entrada no cache
    // ou validando que o cache armazena por token
    try {
      var field = LegacyAuthenticationClient.class.getDeclaredField("authCache");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var map = (Map<String, Object>) field.get(client);

      var cachedClass = Class.forName("br.com.mncheck.LegacyAuthenticationClient$CachedUser");
      var constructor = cachedClass.getDeclaredConstructor(
          LegacyAuthenticationClient.AuthenticatedUser.class, java.time.Instant.class);
      constructor.setAccessible(true);
      Object cachedUser = constructor.newInstance(user, java.time.Instant.now().plusSeconds(60));

      map.put(token, cachedUser);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    assertEquals(1, client.cacheSize());

    // requireInventoryUser deve retornar o usuário em cache imediatamente
    LegacyAuthenticationClient.AuthenticatedUser resolved = client.requireInventoryUser(token);
    assertNotNull(resolved);
    assertEquals("1", resolved.id());
    assertEquals("Operador Teste", resolved.name());
    assertEquals("admin", resolved.role());
    assertEquals(1, client.cacheSize());
  }

  @Test
  void expiresCacheAfterTtl() {
    String token = "Bearer token-expirado";
    LegacyAuthenticationClient.AuthenticatedUser user = new LegacyAuthenticationClient.AuthenticatedUser(
        "2", "Operador Expirado", "stock");

    try {
      var field = LegacyAuthenticationClient.class.getDeclaredField("authCache");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var map = (Map<String, Object>) field.get(client);

      var cachedClass = Class.forName("br.com.mncheck.LegacyAuthenticationClient$CachedUser");
      var constructor = cachedClass.getDeclaredConstructor(
          LegacyAuthenticationClient.AuthenticatedUser.class, java.time.Instant.class);
      constructor.setAccessible(true);
      // Expirado há 10 segundos
      Object cachedUser = constructor.newInstance(user, java.time.Instant.now().minusSeconds(10));
      map.put(token, cachedUser);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Como expirou, o cliente tentará chamar o servidor legado (que não está de pé no teste unitário),
    // resultando em AuthenticationException 503 ("Não foi possível validar a sessão atual.")
    var error = assertThrows(LegacyAuthenticationClient.AuthenticationException.class, () ->
        client.requireInventoryUser(token));
    assertEquals(503, error.status(), "Tentativa de revalidação com servidor offline deve falhar com 503");
  }

  @Test
  void differentTokensDoNotShareUser() {
    String tokenA = "Bearer token-A";
    String tokenB = "Bearer token-B";

    LegacyAuthenticationClient.AuthenticatedUser userA = new LegacyAuthenticationClient.AuthenticatedUser(
        "10", "Operador A", "admin");
    LegacyAuthenticationClient.AuthenticatedUser userB = new LegacyAuthenticationClient.AuthenticatedUser(
        "20", "Operador B", "stock");

    try {
      var field = LegacyAuthenticationClient.class.getDeclaredField("authCache");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var map = (Map<String, Object>) field.get(client);

      var cachedClass = Class.forName("br.com.mncheck.LegacyAuthenticationClient$CachedUser");
      var constructor = cachedClass.getDeclaredConstructor(
          LegacyAuthenticationClient.AuthenticatedUser.class, java.time.Instant.class);
      constructor.setAccessible(true);

      map.put(tokenA, constructor.newInstance(userA, java.time.Instant.now().plusSeconds(60)));
      map.put(tokenB, constructor.newInstance(userB, java.time.Instant.now().plusSeconds(60)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    assertEquals(2, client.cacheSize());
    assertEquals("Operador A", client.requireInventoryUser(tokenA).name());
    assertEquals("Operador B", client.requireInventoryUser(tokenB).name());
  }

  @Test
  void benchmarkAuthCachePerformance() {
    String token = "Bearer bench-token";
    LegacyAuthenticationClient.AuthenticatedUser user = new LegacyAuthenticationClient.AuthenticatedUser(
        "1", "Operador Benchmark", "admin");

    try {
      var field = LegacyAuthenticationClient.class.getDeclaredField("authCache");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var map = (Map<String, Object>) field.get(client);
      var cachedClass = Class.forName("br.com.mncheck.LegacyAuthenticationClient$CachedUser");
      var constructor = cachedClass.getDeclaredConstructor(
          LegacyAuthenticationClient.AuthenticatedUser.class, java.time.Instant.class);
      constructor.setAccessible(true);
      map.put(token, constructor.newInstance(user, java.time.Instant.now().plusSeconds(60)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Warm-up
    for (int i = 0; i < 1000; i++) {
      client.requireInventoryUser(token);
    }

    int iterations = 100_000;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      client.requireInventoryUser(token);
    }
    long elapsedNanos = System.nanoTime() - start;
    double avgMicros = (elapsedNanos / (double) iterations) / 1000.0;
    System.out.printf("[BENCHMARK] Autenticação em cache: %.4f µs por operação (%d iterações em %d ms)%n",
        avgMicros, iterations, elapsedNanos / 1_000_000);
    assertTrue(avgMicros < 10.0, "Autenticação em cache deve ser ultrarrápida (< 10 µs)");
  }
}

