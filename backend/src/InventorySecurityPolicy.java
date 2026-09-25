package br.com.mncheck;

/**
 * Política central de proteção e sigilo do modo cego do inventário.
 *
 * Regras fundamentais:
 * 1. O sigilo do inventário CEGO termina APENAS quando o inventário estiver com status 'ENCERRADO'.
 * 2. Em inventário CEGO: todas as rodadas (R1, R2, etc.) permanecem protegidas enquanto o inventário não estiver ENCERRADO.
 * 3. Encerrar uma rodada ('FINALIZADA') NÃO encerra o sigilo em inventário CEGO; apenas o encerramento formal da sessão ('ENCERRADO') encerra o sigilo.
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
    return "CEGO".equalsIgnoreCase(inventoryMode);
  }

  /**
   * Determina se a investigação de uma divergência está em contexto protegido.
   */
  public static boolean isInvestigationProtected(String inventoryMode, String inventoryStatus, int roundNumber) {
    if (isClosed(inventoryStatus)) {
      return false;
    }
    return "CEGO".equalsIgnoreCase(inventoryMode);
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
