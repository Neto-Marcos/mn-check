package br.com.mncheck;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DatabaseUrlParser {
  private DatabaseUrlParser() {}

  static JdbcConfig parse(String rawDatabaseUrl) {
    String databaseUrl = normalizeInput(rawDatabaseUrl);
    if (databaseUrl.startsWith("jdbc:postgresql:")) {
      return new JdbcConfig(
          normalizeParameters(databaseUrl),
          System.getenv().getOrDefault("DATABASE_USER", ""),
          System.getenv().getOrDefault("DATABASE_PASSWORD", "")
      );
    }

    URI uri;
    try {
      uri = URI.create(databaseUrl);
    } catch (IllegalArgumentException error) {
      throw new DatabaseUrlException(
          "DATABASE_URL inválida. Copie a connection string do Neon sem o comando psql.",
          error
      );
    }
    if (!List.of("postgres", "postgresql").contains(uri.getScheme())) {
      throw new DatabaseUrlException(
          "DATABASE_URL deve começar com postgres://, postgresql:// ou jdbc:postgresql://."
      );
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new DatabaseUrlException("DATABASE_URL não contém o servidor do Neon.");
    }

    String userInfo = uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo();
    int separator = userInfo.indexOf(':');
    String username = separator >= 0 ? userInfo.substring(0, separator) : userInfo;
    String password = separator >= 0 ? userInfo.substring(separator + 1) : "";
    username = decodeCredential(username);
    password = decodeCredential(password);

    int port = uri.getPort() > 0 ? uri.getPort() : 5432;
    String path = uri.getRawPath();
    if (path == null || path.isBlank() || "/".equals(path)) {
      throw new DatabaseUrlException("DATABASE_URL não contém o nome do banco de dados.");
    }
    String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path;
    if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
      jdbcUrl += "?" + uri.getRawQuery();
    }
    return new JdbcConfig(normalizeParameters(jdbcUrl), username, password);
  }

  private static String normalizeInput(String rawDatabaseUrl) {
    if (rawDatabaseUrl == null || rawDatabaseUrl.isBlank()) {
      throw new DatabaseUrlException("DATABASE_URL não foi configurada.");
    }
    String value = rawDatabaseUrl.trim();
    if (value.regionMatches(true, 0, "psql ", 0, 5)) {
      value = value.substring(5).trim();
    }
    if ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.substring(1, value.length() - 1).trim();
    }
    return value;
  }

  private static String normalizeParameters(String jdbcUrl) {
    int queryIndex = jdbcUrl.indexOf('?');
    String base = queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) : jdbcUrl;
    String query = queryIndex >= 0 ? jdbcUrl.substring(queryIndex + 1) : "";
    List<String> parameters = new ArrayList<>();
    boolean hasSslMode = false;

    for (String parameter : query.split("&")) {
      if (parameter.isBlank()) continue;
      int separator = parameter.indexOf('=');
      String name = separator >= 0 ? parameter.substring(0, separator) : parameter;
      String value = separator >= 0 ? parameter.substring(separator + 1) : "";
      if ("sslmode".equalsIgnoreCase(name)) hasSslMode = true;
      if ("channel_binding".equalsIgnoreCase(name)) name = "channelBinding";
      parameters.add(name + (separator >= 0 ? "=" + value : ""));
    }
    if (!hasSslMode) parameters.add("sslmode=require");
    return base + "?" + String.join("&", parameters);
  }

  private static String decodeCredential(String value) {
    return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  record JdbcConfig(String url, String username, String password) {}

  static final class DatabaseUrlException extends RuntimeException {
    DatabaseUrlException(String message) {
      super(message);
    }

    DatabaseUrlException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
