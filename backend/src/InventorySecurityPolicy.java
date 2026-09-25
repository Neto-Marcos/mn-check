package br.com.mncheck;

/**
 * Política central de proteção e sigilo do modo cego do inventário.
 *
 * Regras fundamentais:
 * 1. O sigilo do inventário CEGO ou da Rodada 2 termina APENAS quando o inventário estiver com status 'ENCERRADO'.
 * 2. Rodada 1 de inventário CEGO: protegida enquanto não estiver ENCERRADO.
 * 3. Rodada 2: SEMPRE cega/protegida (independente de ser modo NORMAL ou CEGO) enquanto não estiver ENCERRADO.
 * 4. Nenhum endpoint operacional pode revelar saldo esperado, diferença ou conformidade/divergência em contexto protegido.
 * 5. Após o encerramento formal ('ENCERRADO'), o resultado histórico oficial e imutável pode ser consultado na íntegra.
 */
public final class InventorySecurityPolicy {

  private InventorySecurityPolicy() {}

  /**
   * Determina se o contexto da rodada está protegido.
   *
   * @param inventoryMode Modo do inventário ('NORMAL', 'CEGO', etc.)
   * @param inventoryStatus Status atual do inventário ('ABERTO', 'EM_CONTAGEM', 'ENCERRADO', etc.)
   * @param roundNumber Número da rodada (1, 2, etc.)
   * @return true se os campos sensíveis (saldo, diferença, divergência) devem ser ocultados
   */
  public static boolean isRoundProtected(String inventoryMode, String inventoryStatus, int roundNumber) {
    if (isClosed(inventoryStatus)) {
      return false;
    }
    boolean isBlind = "CEGO".equalsIgnoreCase(inventoryMode);
    if (roundNumber == 1) {
      return isBlind;
    }
    if (roundNumber >= 2) {
      return true; // R2 é SEMPRE cega
    }
    return isBlind;
  }

  /**
   * Determina se a investigação de uma divergência está em contexto protegido.
   */
  public static boolean isInvestigationProtected(String inventoryMode, String inventoryStatus, int roundNumber) {
    return isRoundProtected(inventoryMode, inventoryStatus, roundNumber);
  }

  /**
   * Determina se o inventário está em contexto protegido global (ex: validação de fechamento, resumo geral).
   */
  public static boolean isInventoryProtected(String inventoryMode, String inventoryStatus) {
    if (isClosed(inventoryStatus)) {
      return false;
    }
    return "CEGO".equalsIgnoreCase(inventoryMode);
  }

  /**
   * Retorna true se o inventário já foi formalmente encerrado.
   */
  public static boolean isClosed(String inventoryStatus) {
    return "ENCERRADO".equalsIgnoreCase(inventoryStatus);
  }
}
