package br.com.mncheck;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Order(Ordered.LOWEST_PRECEDENCE)
public class LegacyProxyController {
  private static final Set<String> BLOCKED_HEADERS = Set.of(
      "connection", "content-length", "host", "transfer-encoding"
  );
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  @RequestMapping("/**")
  public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws Exception {
    String query = request.getQueryString();
    String target = "http://127.0.0.1:" + MnCheckApplication.LEGACY_PORT
        + request.getRequestURI() + (query == null ? "" : "?" + query);
    byte[] body = request.getInputStream().readAllBytes();

    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(target))
        .timeout(Duration.ofSeconds(130))
        .method(
            request.getMethod(),
            body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)
        );
    request.getHeaderNames().asIterator().forEachRemaining(name -> {
      if (!BLOCKED_HEADERS.contains(name.toLowerCase())) {
        request.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
      }
    });

    HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    HttpHeaders headers = new HttpHeaders();
    response.headers().map().forEach((name, values) -> {
      if (!BLOCKED_HEADERS.contains(name.toLowerCase())) headers.put(name, List.copyOf(values));
    });
    return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body());
  }
}
