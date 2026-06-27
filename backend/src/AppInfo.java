package br.com.mncheck;

import java.util.Map;

public final class AppInfo {
  public static final String NAME = "MN - Check";
  public static final String VERSION = "2.0.0";
  public static final String BUILD_AT = env("RAILWAY_DEPLOYMENT_CREATED_AT", env("BUILD_DATE", "local"));
  public static final String COMMIT = env("RAILWAY_GIT_COMMIT_SHA", env("GIT_COMMIT", "local"));

  private AppInfo() {}

  public static Map<String, Object> versionPayload() {
    return Map.of(
        "app", NAME,
        "version", VERSION,
        "buildAt", BUILD_AT,
        "commit", COMMIT
    );
  }

  public static Map<String, Object> healthPayload() {
    return Map.of(
        "app", NAME,
        "version", VERSION,
        "buildAt", BUILD_AT,
        "commit", COMMIT,
        "status", "ok"
    );
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
