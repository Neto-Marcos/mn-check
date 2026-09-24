package br.com.mncheck;

import java.util.LinkedHashMap;
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
    DatabaseUrlParser.JdbcConfig database = DatabaseUrlParser.parse(System.getenv("DATABASE_URL"));
    SpringApplication application = new SpringApplication(MnCheckApplication.class);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("spring.servlet.multipart.max-file-size", "12MB");
    properties.put("spring.servlet.multipart.max-request-size", "12MB");
    properties.put("server.forward-headers-strategy", "framework");
    properties.put("spring.datasource.url", database.url());
    properties.put("spring.datasource.username", database.username());
    properties.put("spring.datasource.password", database.password());
    properties.put("spring.flyway.enabled", "true");
    properties.put("spring.flyway.locations", "classpath:db/migration");
    properties.put("spring.flyway.baseline-on-migrate", "true");
    properties.put("spring.flyway.baseline-version", "0");
    properties.put("spring.flyway.validate-on-migrate", "true");
    application.setDefaultProperties(properties);
    application.run(args);
    // Flyway termina durante a inicialização do contexto Spring. Só depois o legado
    // executa sua criação compatível de schema, evitando alterações concorrentes.
    startLegacyServer();
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
