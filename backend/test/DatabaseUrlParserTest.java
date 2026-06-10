package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseUrlParserTest {
  @Test
  void convertsNeonUrlForPostgresJdbc() {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(
        "postgresql://neondb_owner:secret@ep-test-pooler.us-east-2.aws.neon.tech/neondb"
            + "?sslmode=require&channel_binding=require"
    );

    assertEquals("neondb_owner", config.username());
    assertEquals("secret", config.password());
    assertTrue(config.url().startsWith(
        "jdbc:postgresql://ep-test-pooler.us-east-2.aws.neon.tech:5432/neondb?"
    ));
    assertTrue(config.url().contains("sslmode=require"));
    assertTrue(config.url().contains("channelBinding=require"));
  }

  @Test
  void acceptsPsqlCommandCopiedFromNeon() {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(
        "psql 'postgresql://owner:p%40ss@ep-test.neon.tech/neondb?sslmode=require'"
    );

    assertEquals("owner", config.username());
    assertEquals("p@ss", config.password());
    assertTrue(config.url().contains("sslmode=require"));
  }

  @Test
  void addsSslWhenMissing() {
    DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(
        "postgresql://owner:secret@ep-test.neon.tech/neondb"
    );

    assertTrue(config.url().endsWith("?sslmode=require"));
  }
}
