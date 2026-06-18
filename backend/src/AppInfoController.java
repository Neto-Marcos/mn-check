package br.com.mncheck;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppInfoController {
  @GetMapping("/api/version")
  public Map<String, Object> version() {
    return AppInfo.versionPayload();
  }

  @GetMapping("/api/health")
  public Map<String, Object> health() {
    return AppInfo.healthPayload();
  }
}
