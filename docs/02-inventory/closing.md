# Fechamento Formal e Resultado do Inventário 3.0

O Fechamento Formal é o estágio definitivo do ciclo de vida de uma sessão de inventário no MN-Check 3.0. Ele transforma a sessão operacional em um registro histórico consolidado, imutável e auditável, congelando os resultados finais de contagem física, divergências, sobras, faltas e investigações associadas.

---

## 1. Ciclo de Vida Completo do Inventário

```
PREPARAR (Rascunho/Aberto)
    │
    ▼
CONTAR R1 (Rodada 1 aberta - Normal ou Cega)
    │
    ▼
APURAR (Encerramento de R1 - Cálculo de conformes, divergentes e faltas/sobras)
    │
    ▼
RECONTAR R2 (Rodada 2 - Escopo delimitado a divergentes/não contados)
    │
    ▼
INVESTIGAR (Tratativa de divergências confirmadas, evidências, causas e resoluções)
    │
    ▼
FECHAR (Validação de conformidade prévia e fechamento formal Normal ou Excepcional)
    │
    ▼
HISTÓRICO (Sessão ENCERRADA, estritamente imutável, com snapshot oficial por item)
```

---

## 2. Pré-Validação de Fechamento

Antes do encerramento formal, o endpoint de validação verifica a integridade operacional da sessão:

`GET /api/inventarios/{id}/fechamento/validacao?branchCode={branchCode}`

### Pendências Impeditivas Mapeadas

1. **`RODADA_ATIVA`**: Uma rodada (R1 ou R2) ainda está em andamento (`EM_ANDAMENTO`). Deve ser finalizada e apurada antes do fechamento.
2. **`SEM_APURACAO_R1`**: A Rodada 1 não foi finalizada nem apurada.
3. **`RODADA_2_PENDENTE`**: A Rodada 2 foi criada mas não foi finalizada.
4. **`DIVERGENCIA_SEM_INVESTIGACAO`**: Existem itens com divergência confirmada pós-apuração sem nenhuma investigação associada criada.
5. **`INVESTIGACOES_PENDENTES`**: Existem investigações ativas (`PENDENTE`, `EM_INVESTIGACAO`, `AGUARDANDO_EVIDENCIA`) que não foram resolvidas nem encerradas com sem causa formal.
6. **`ITENS_NAO_CONTADOS`**: Existem itens no escopo do inventário que não foram bipados ou contados.
7. **`INVENTARIO_JA_ENCERRADO`**: O inventário já se encontra em status `ENCERRADO`.
8. **`INVENTARIO_CANCELADO`**: O inventário foi cancelado e não pode ser encerrado.

Se qualquer uma dessas pendências existir, o campo `podeFechar` retornará `false` e o fechamento normal será bloqueado.

---

## 3. Modos de Fechamento

O fechamento é acionado através do endpoint:

`POST /api/inventarios/{id}/fechar`

### 3.1. Fechamento Normal (`tipo: "NORMAL"`)
- **Requisito**: `podeFechar === true` (zero pendências impeditivas).
- Se houver pendências ativas, a requisição é rejeitada com `HTTP 409 Conflict`.
- Executado por qualquer usuário operacional autenticado com permissão de inventário (`admin` ou `stock`).

### 3.2. Fechamento Excepcional (`tipo: "EXCEPCIONAL"`)
- Permite encerrar a sessão mesmo com pendências impeditivas ativas (ex: divergências não investigadas, itens não contados).
- **Controle de Acesso Rigoroso**: Apenas usuários com perfil de **Administrador** (`role = 'admin'`) podem executar.
  - Tentativas por usuários não administradores são rejeitadas com `HTTP 403 Forbidden`.
- **Justificativa Obrigatória**: Requer preenchimento de justificativa formal com **no mínimo 15 caracteres**.
  - Justificativa ausente ou menor que 15 caracteres é rejeitada com `HTTP 400 Bad Request`.
- **Snapshot de Compliance**: As pendências existentes no instante do fechamento são serializadas em JSON (`pendencias_snapshot`) e gravadas permanentemente na tabela `resultados_inventario`.

---

## 4. Regras de Cálculo Físico e Diferença

Para cada item presente no inventário (`inventario_itens`), o resultado consolidado (`resultado_inventario_itens`) adota as seguintes regras determinísticas:

1. **Precedência da Contagem Física**:
   - Se houve Rodada 2 e o item possui apuração em R2: utiliza `r2.quantidadeFisica`.
   - Caso contrário: utiliza `r1.quantidadeFisica`.
   - Se o item não foi contado em nenhuma rodada: o campo `quantidade_fisica_final` é gravado como **`null`** (e **NÃO** 0), e o estado final é gravado como `NAO_CONTADO`.

2. **Cálculo da Diferença**:
   - `diferenca_final = quantidade_fisica_final - saldo_snapshot`.
   - Se `quantidade_fisica_final` for `null`: `diferenca_final` é gravada como **`null`**.
   - Se `diferenca_final == 0`: estado `CONFORME` (ou `CONFORME_APOS_RECONTAGEM` se veio de R2). Tipo de divergência `CONFORME`.
   - Se `diferenca_final > 0`: tipo de divergência `SOBRA` (+X un).
   - Se `diferenca_final < 0`: tipo de divergência `FALTA` (-X un).

3. **Magnitude Positiva nas Métricas Consolidadas**:
   - Na tabela `resultados_inventario`:
     - `quantidade_falta = Math.abs(soma das faltas)` (sempre um número inteiro $\ge 0$).
     - `quantidade_sobra = soma das sobras` (sempre um número inteiro $\ge 0$).

---

## 5. Garantia de Imutabilidade Estrita

Após o fechamento, o inventário atinge o estado terminal `ENCERRADO`:

- **Transições de Status Bloqueadas**: A sessão não pode ser reaberta, cancelada ou retornada a qualquer estado operacional.
- **Bloqueio de Ocorrências**: Nenhuma nova ocorrência (`ocorrencias_contagem`) pode ser inserida na sessão (`HTTP 409 Conflict`).
- **Bloqueio de Rodadas e Recontagens**: Nenhuma rodada pode ser iniciada, alterada ou encerrada (`HTTP 409 Conflict`).
- **Bloqueio de Investigações**:
  - Nenhuma nova investigação pode ser criada.
  - Investigações existentes não podem ter status alterado, causa suspeita atualizada, evidências adicionadas, vínculos criados, resoluções aplicadas ou reaberturas efetuadas.
  - Todas as 9 rotas de mutação de investigação retornam `HTTP 409 Conflict` imediatamente ao verificar que a sessão raiz está em status `ENCERRADO`.

---

## 6. Estrutura de Banco de Dados (Migration V7)

### `resultados_inventario`
Registra o sumário oficial da sessão encerrada:
- `total_itens`, `total_unidades_snapshot`
- `itens_conformes_r1`, `itens_enviados_r2`, `itens_conformes_apos_r2`, `divergencias_confirmadas`
- `investigacoes_resolvidas`, `investigacoes_sem_causa`, `investigacoes_pendentes`
- `itens_nao_contados`, `itens_com_falta`, `itens_com_sobra`, `quantidade_falta`, `quantidade_sobra`
- `inicio_em`, `fechado_em`, `duracao_segundos`
- `tipo_fechamento` (`NORMAL`, `EXCEPCIONAL`)
- `justificativa_excepcional`, `pendencias_snapshot` (JSONB)
- `fechado_por_id`, `fechado_por_nome`

### `resultado_inventario_itens`
Snapshot definitivo por item no escopo do inventário:
- `sku`, `descricao`, `saldo_snapshot`
- `r1_fisico`, `r1_diferenca`, `r1_estado`
- `houve_r2`, `r2_fisico`, `r2_diferenca`, `r2_estado`
- `quantidade_fisica_final`, `diferenca_final`
- `estado_final` (`CONFORME`, `CONFORME_APOS_RECONTAGEM`, `DIVERGENCIA_CONFIRMADA`, `NAO_CONTADO`)
- `tipo_divergencia` (`CONFORME`, `FALTA`, `SOBRA`, `NAO_CONTADO`)
- `investigacao_id`, `status_investigacao`, `causa_confirmada`, `conclusao`
- `detalhes_localizacao_condicao` (JSONB)

### `eventos_inventario`
Log append-only de auditoria da sessão:
- Tipos de eventos: `CRIADO`, `ABERTO`, `INICIADO`, `RODADA_INICIADA`, `RODADA_ENCERRADA`, `RECONTAGEM_CRIADA`, `INVENTARIO_ENCERRADO`, `CANCELADO`.
