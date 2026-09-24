package br.com.mncheck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class CountMathTest {
  @Test
  void accountedQuantityIncludesEveryOperationalColumn() {
    PostgresDatabase.CountRow row = new PostgresDatabase.CountRow(
        "75329.1.2", "Produto de teste", 100, 80, 7, 5, 3);

    assertEquals(95, row.accountedQuantity());
    assertEquals(-5, row.accountedQuantity() - row.systemBalance());
  }

  @Test
  void negativeOtherQuantityReducesAccountedTotal() {
    PostgresDatabase.CountRow row = new PostgresDatabase.CountRow(
        "75329.1.2", "Produto de teste", 100, 100, 0, 0, -60);

    assertEquals(40, row.accountedQuantity());
    assertEquals(-60, row.accountedQuantity() - row.systemBalance());
  }
}
