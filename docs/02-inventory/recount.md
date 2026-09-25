# Recontagem Operacional e Apuração — Rodada 2 (MN-Check 3.0)

## 1. Visão Geral

A Rodada 2 do MN-Check 3.0 tem como objetivo exclusivo a recontagem imparcial de itens que apresentaram divergência ou que não foram contados na Rodada 1.

Fluxo operacional:
```
Rodada 1 (Finalizada) 
  → Apuração da R1 
  → Seleção de Itens Elegíveis (Divergentes / Não Contados) 
  → Iniciar Rodada 2 
  → Recontagem Cega 
  → Encerrar Rodada 2 
  → Apuração Final
```

---

## 2. Criação da Rodada 2 e Delimitação de Escopo

- **Criação Explícita:** A Rodada 2 só pode ser criada via requisição `POST /api/inventarios/{id}/criar-recontagem` enviando a lista de `itemIds`. Nunca é gerada automaticamente ou via `GET`.
- **Validação de Elegibilidade:**
  - Itens com estado `CONFORME` na Rodada 1 **nunca** entram na recontagem. A inclusão de um item conforme resulta em `HTTP 400 Bad Request`.
  - Itens elegíveis são `DIVERGENTE` e `NAO_CONTADO`.
- **Tabela de Escopo (`rodada_itens`):**
  - Armazena os pares `(rodada_id, inventario_item_id)` e o motivo (`origem_motivo`: `DIVERGENCIA` ou `NAO_CONTADO_R1`).
  - O endpoint `GET /api/inventarios/{id}/itens` filtra e retorna **somente** os itens presentes em `rodada_itens`.
  - Qualquer tentativa de bipar ou contar itens fora do escopo da R2 é rejeitada com `HTTP 409 Conflict`.

---

## 3. Cegueira Estrutural Obrigatória (Blind Mode)

Para garantir total imparcialidade na recontagem:
- **A Rodada 2 é SEMPRE CEGA**, independentemente de a sessão de inventário ter sido configurada como `NORMAL` ou `CEGO`.
- O DTO operacional da R2 sanitiza e remove no backend:
  - `saldoSnapshot` (retornado como `null`);
  - Quantidade contada na Rodada 1;
  - Diferença apurada na Rodada 1;
  - Falta ou sobra estimada.
- A sanitização é garantida pelo servidor e não depende de ocultação visual no cliente.

---

## 4. Encerramento e Resolução de Estados

Ao encerrar a Rodada 2 via `POST /api/inventarios/{id}/rodadas/{rodadaId}/encerrar`:
- As ocorrências da Rodada 2 continuam em modelo append-only em `ocorrencias_contagem`.
- A apuração da Rodada 2 compara a contagem física da R2 com o `saldo_snapshot` original:
  - Se `quantidade_fisica_R2 == saldo_snapshot`:
    $\rightarrow$ **`CONFORME_APOS_RECONTAGEM`**
  - Se `quantidade_fisica_R2 != saldo_snapshot`:
    $\rightarrow$ **`DIVERGENCIA_CONFIRMADA`**
  - Se não houver contagem (encerramento forçado):
    $\rightarrow$ **`NAO_CONTADO`**

As apurações da Rodada 1 e Rodada 2 são independentes e auditáveis, preservando todo o histórico temporal da contagem.
