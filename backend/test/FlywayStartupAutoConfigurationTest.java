package br.com.mncheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FlywayStartupAutoConfigurationTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
      // Unreachable local test address: the unit-test strategy must not access a database.
      .withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1:1/startup_unit_test",
          "spring.datasource.username=test", "spring.datasource.password=test",
          "spring.flyway.locations=classpath:db/migration",
          "spring.flyway.baseline-on-migrate=false", "spring.flyway.validate-on-migrate=true");

  @Test
  void configuresDataSourceAndInvokesFlywayInitializerAtStartup() {
    AtomicBoolean initialized = new AtomicBoolean();
    context.withBean(FlywayMigrationStrategy.class, () -> flyway -> {
      assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
      assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
      initialized.set(true);
    }).run(application -> {
      assertThat(application).hasNotFailed().hasSingleBean(DataSource.class)
          .hasSingleBean(Flyway.class).hasSingleBean(FlywayMigrationInitializer.class);
      assertThat(initialized).isTrue();
    });
  }

  @Test
  void explicitDisableDoesNotInvokeFlyway() {
    AtomicBoolean initialized = new AtomicBoolean();
    context.withPropertyValues("spring.flyway.enabled=false")
        .withBean(FlywayMigrationStrategy.class, () -> flyway -> initialized.set(true))
        .run(application -> {
          assertThat(application).hasNotFailed().hasSingleBean(DataSource.class)
              .doesNotHaveBean(Flyway.class).doesNotHaveBean(FlywayMigrationInitializer.class);
          assertThat(initialized).isFalse();
        });
  }

  @Test
  void migrationFailurePreventsStartup() {
    context.withBean(FlywayMigrationStrategy.class, () -> flyway -> {
      throw new FlywayException("Expected unit-test validation failure");
    }).run(application -> assertThat(application).hasFailed());
  }
}
