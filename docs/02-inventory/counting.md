# Arquitetura de Contagem Operacional — MN-Check 3.0

## 1. Visão Geral

O módulo de Inventário e Contagem 3.0 introduz o ciclo operacional formal de inventários com rodadas, imutabilidade de contagens, idempotência estrita e proteção auditável.

Fluxo operacional alvo:
```
Filial → Inventários → Criar/Selecionar Sessão → Abrir → Iniciar (Rodada 1) → Contagem Operacional
```

A regra de ouro do sistema é:
**NENHUMA CONTAGEM PODE DESAPARECER.**

---

## 2. Rodadas de Contagem (`rodadas_contagem`)

As rodadas organizam as fases de contagem de um inventário:
- **Tipos de rodada:** `CONTAGEM`, `RECONTAGEM`, `INVESTIGACAO` (no Dia 4, o foco é na Rodada 1 do tipo `CONTAGEM`).
- **Status:** `EM_ANDAMENTO`, `FINALIZADA`, `CANCELADA`.
- **Criação Determinística:** A transição da sessão de `ABERTO` para `EM_CONTAGEM` cria automaticamente a `Rodada 1` dentro da mesma transação de banco. Caso ocorra erro ao criar a rodada, a transição sofre rollback completo.
- **Leituras Seguras:** O endpoint `GET /api/inventarios/{id}/rodada-ativa` é puramente de leitura e nunca gera rodadas como efeito colateral.

---

## 3. Ocorrências e Imutabilidade (`ocorrencias_contagem`)

Ao invés de atualizar linhas mutáveis em tabelas de saldo, toda leitura/bipagem gera um registro imutável em `ocorrencias_contagem`:
- `rodada_id`: vínculo com a rodada ativa (`ON DELETE RESTRICT`).
- `inventario_item_id`: vínculo com o item congelado no snapshot da sessão (`ON DELETE RESTRICT`).
- `sku`: SKU contado.
- `quantidade`: quantidade numérica (inteiro $\ge 0$).
- `categoria`: `BOA`, `AVARIA`, `ASSISTENCIA`, `OUTROS`.
- `tipo_acao`: `DEFINIR`, `SOMAR`, `CORRECAO`.
- `operador`: nome do operador autenticado obtido via `LegacyAuthenticationClient` (`/api/bootstrap`) a partir do header `Authorization` (nunca aceito do payload do cliente).
- `client_event_id`: UUID v4 gerado pelo cliente para garantia de idempotência.
- `origem`: `SCANNER`, `MANUAL`, `OFFLINE`.
- `dispositivo`: identificação opcional do terminal/coletor.
- `client_timestamp` e `server_timestamp`: rastreabilidade temporal.
- `referencia_id`: referência opcional à ocorrência anterior em casos de `CORRECAO` (`ON DELETE RESTRICT`).

---

## 4. Semântica de Projeção do Valor Vigente

Quando há múltiplas ocorrências do mesmo SKU na mesma rodada, nenhuma ocorrência é excluída ou sobrescrita.

Para obter a quantidade projetada atual de um SKU em uma rodada:
1. Carregam-se todas as ocorrências do item ordenadas cronologicamente por `server_timestamp ASC, id ASC`.
2. Para cada categoria (`BOA`, `AVARIA`, etc.):
   - Se `tipo_acao == 'DEFINIR'`: a quantidade da categoria torna-se este valor.
   - Se `tipo_acao == 'SOMAR'`: o valor é somado à quantidade acumulada da categoria.
   - Se `tipo_acao == 'CORRECAO'`: a quantidade da categoria é ajustada diretamente para este novo valor, e o registro armazena `referencia_id` apontando para a ocorrência sendo corrigida.
3. A quantidade total do item é a soma das quantidades projetadas de suas categorias.

---

## 5. Fonte da Verdade do Progresso

O progresso da rodada não é computado via flags mutáveis em `inventario_itens.estado`.

A fonte oficial e auditável é exclusivamente `ocorrencias_contagem`:
- **Total de SKUs:** quantidade de linhas em `inventario_itens` para a sessão.
- **SKUs Contados:** quantidade de `inventario_item_id` distintos presentes em `ocorrencias_contagem` para a `rodada_id`.
- **SKUs Pendentes:** `Total de SKUs - SKUs Contados`.
- **Percentual:** `(SKUs Contados * 100) / Total de SKUs`.

---

## 6. Contagem Cega REAL

Para evitar viés de contagem ou vazamento de saldos esperados:
- Se `inventarios.modo == 'CEGO'`, os endpoints:
  - `GET /api/inventarios/{id}`
  - `GET /api/inventarios/{id}/itens`
  - `POST /api/inventarios/{id}/rodadas/{rodadaId}/contagens`
  retornam estruturalmente `saldoSnapshot: null` (ou omitem o campo).
- A diferença (`diferenca`) não é exposta na interface de contagem cega.

---

## 7. Idempotência e Concorrência

- **PostgreSQL Unique Constraint:** `UNIQUE (client_event_id)` na tabela `ocorrencias_contagem`.
- **Tratamento no Backend:** `INSERT ... ON CONFLICT (client_event_id) DO NOTHING RETURNING ...`.
- Caso ocorra colisão por reenvio de pacote ou conexão instável, o backend recupera a ocorrência existente e responde HTTP 200 de forma idempotente, sem duplicar o evento.
- **Concorrência:** Operadores diferentes ou simultâneos gravam ocorrências independentes via append-only, eliminando o risco de "last save wins" no histórico.

---

## 8. Preservação do Fluxo Legado 2.3.6

O fluxo de contagem legado (substituição direta de lote via `/api/contagem` e `estoque_produtos`) e as rotas de conferência de carga (`/api/conferencia`, separação e rotas) permanecem 100% funcionais e preservados sem quebras de contrato.
