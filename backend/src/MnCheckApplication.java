package br.com.mncheck;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MnCheckApplication {
  static final int LEGACY_PORT = 4174;

  public static void main(String[] args) {
    int publicPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "4137"));
    System.setProperty("server.port", String.valueOf(publicPort));
    System.setProperty("mmcheck.legacy.port", String.valueOf(LEGACY_PORT));
    startLegacyServer();
    SpringApplication application = new SpringApplication(MnCheckApplication.class);
    application.setDefaultProperties(Map.of(
        "spring.servlet.multipart.max-file-size", "12MB",
        "spring.servlet.multipart.max-request-size", "12MB",
        "server.forward-headers-strategy", "framework"
    ));
    application.run(args);
  }

  private static void startLegacyServer() {
    Thread legacyThread = new Thread(() -> {
      try {
        MmCheckServer.main(new String[0]);
      } catch (Exception error) {
        error.printStackTrace();
        System.exit(1);
      }
    }, "mn-check-legacy-server");
    legacyThread.setDaemon(true);
    legacyThread.start();
  }
}
