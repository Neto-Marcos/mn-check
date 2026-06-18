package br.com.mncheck;

import java.util.Map;

public final class AppInfo {
  public static final String NAME = "MN - Check";
  public static final String VERSION = "1.8.5";

  private AppInfo() {}

  public static Map<String, Object> versionPayload() {
    return Map.of("app", NAME, "version", VERSION);
  }

  public static Map<String, Object> healthPayload() {
    return Map.of(
        "app", NAME,
        "version", VERSION,
        "status", "ok"
    );
  }
}
