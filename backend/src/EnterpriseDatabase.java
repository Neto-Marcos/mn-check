package br.com.mncheck;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC gateway for the normalized enterprise module. */
public final class EnterpriseDatabase {
  @FunctionalInterface
  public interface TransactionWork<T> {
    T apply(Connection connection) throws Exception;
  }

  private final String jdbcUrl;
  private final String username;
  private final String password;

  public EnterpriseDatabase(String databaseUrl) {
    if (databaseUrl == null || databaseUrl.isBlank()) {
      throw new EnterpriseException(503, "DATABASE_URL não foi configurada.");
    }
    try {
      Class.forName("org.postgresql.Driver");
      DatabaseUrlParser.JdbcConfig config = DatabaseUrlParser.parse(databaseUrl);
      jdbcUrl = config.url();
      username = config.username();
      password = config.password();
      migrate();
    } catch (EnterpriseException error) {
      throw error;
    } catch (Exception error) {
      throw new EnterpriseException(503, "Não foi possível preparar o banco empresarial.", error);
    }
  }

  public Connection connect() throws SQLException {
    return username.isBlank()
        ? DriverManager.getConnection(jdbcUrl)
        : DriverManager.getConnection(jdbcUrl, username, password);
  }

  public <T> T transaction(TransactionWork<T> work) {
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        T result = work.apply(connection);
        connection.commit();
        return result;
      } catch (Exception error) {
        connection.rollback();
        if (error instanceof EnterpriseException enterpriseError) throw enterpriseError;
        if (error instanceof SQLException sqlError && "23505".equals(sqlError.getSQLState())) {
          throw new EnterpriseException(409, "O registro já existe ou a operação já foi processada.", error);
        }
        if (error instanceof SQLException sqlError && "23514".equals(sqlError.getSQLState())) {
          throw new EnterpriseException(409, "A movimentação deixaria o saldo em um estado inválido.", error);
        }
        throw new EnterpriseException(500, "Não foi possível concluir a operação.", error);
      }
    } catch (EnterpriseException error) {
      throw error;
    } catch (SQLException error) {
      throw new EnterpriseException(503, "Banco de dados temporariamente indisponível.", error);
    }
  }

  public List<Map<String, Object>> query(String sql, Object... parameters) {
    try (Connection connection = connect()) {
      return query(connection, sql, parameters);
    } catch (SQLException error) {
      throw new EnterpriseException(503, "Não foi possível consultar o banco empresarial.", error);
    }
  }

  public Optional<Map<String, Object>> one(String sql, Object... parameters) {
    List<Map<String, Object>> rows = query(sql, parameters);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public static List<Map<String, Object>> query(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = result.getMetaData();
        while (result.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value = result.getObject(column);
            if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
            row.put(metadata.getColumnLabel(column), value);
          }
          rows.add(row);
        }
        return rows;
      }
    }
  }

  public static Optional<Map<String, Object>> one(Connection connection, String sql, Object... parameters)
      throws SQLException {
    List<Map<String, Object>> rows = query(connection, sql, parameters);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public static int update(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    }
  }

  public static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      Object value = parameters[index];
      if (value instanceof Instant instant) statement.setTimestamp(index + 1, Timestamp.from(instant));
      else statement.setObject(index + 1, value);
    }
  }

  private void migrate() throws Exception {
    String script;
    try (InputStream input = EnterpriseDatabase.class.getResourceAsStream("/enterprise-schema.sql")) {
      if (input == null) throw new IllegalStateException("enterprise-schema.sql não encontrado.");
      script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection = connect(); Statement statement = connection.createStatement()) {
      for (String command : script.split(";\\s*(?:\\r?\\n|$)")) {
        String sql = command.trim();
        if (!sql.isBlank()) statement.execute(sql);
      }
    }
  }

  public static final class EnterpriseException extends RuntimeException {
    private final int status;

    public EnterpriseException(int status, String message) {
      super(message);
      this.status = status;
    }

    public EnterpriseException(int status, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
