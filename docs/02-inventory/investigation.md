# Investigação e Tratativa de Divergências — Dia 6 (MN-Check 3.0)

## 1. Visão Geral

A etapa de **Investigação e Tratativa de Divergências** entra em ação após a apuração das rodadas de contagem (R1 e R2), com foco exclusivo nas divergências confirmadas e itens não localizados.

Fluxo do ciclo operacional:
```
Preparar Sessão 
  → Contar R1 
  → Apurar R1 
  → Recontar R2 (Cega) 
  → Apuração Final 
  → Investigar Divergências (Dia 6) 
  → Resolução e Documentação Auditável (Dia 6)
  → Fechamento Formal e Ajuste ERP (Dia 7)
```

---

## 2. Princípios Fundamentais

1. **Preservação Total da Contagem Física:**
   - A investigação **nunca** altera a contagem física apurada, nem remove ou modifica ocorrências de contagem, rodadas R1/R2, snapshots de saldo ou apurações consolidadas.
   - A investigação existe para **explicar, documentar e respaldar** as divergências físicas encontradas.
2. **Histórico Imutável e Auditável (Append-only Timeline):**
   - Todas as transições de status, alterações de causa suspeita, inclusão de evidências e vínculos entre produtos são registradas na tabela `eventos_investigacao`.
   - Investigações finalizadas não podem sofrer edições silenciosas; reabertura exige justificativa auditada.
3. **Escopo Delimitado:**
   - O Dia 6 **não fecha formalmente a sessão de inventário** e não aplica ajustes no ERP (tarefas reservadas para o Dia 7).

---

## 3. Máquina de Estados da Investigação

Cada divergência apurada possui no máximo uma investigação (`uk_investigacao_apuracao UNIQUE (apuracao_id)`).

```
                      ┌───────────────┐
                      │   PENDENTE    │
                      └───────┬───────┘
                              │ iniciar
                              ▼
                      ┌───────────────┐  pausar / aguardar
                      │EM_INVESTIGACAO│ ─────────────────► ┌────────────────────┐
                      └───────┬───────┘ ◄───────────────── │AGUARDANDO_EVIDENCIA│
                              │            retomar         └─────────┬──────────┘
                  ┌───────────┴───────────┐                          │
                  │ resolver              │ sem causa                │ resolver / sem causa
                  ▼                       ▼                          ▼
           ┌──────────────┐    ┌──────────────────────┐
           │  RESOLVIDA   │    │SEM_CAUSA_IDENTIFICADA│
           └──────┬───────┘    └──────────┬───────────┘
                  │                       │
                  └───────────┬───────────┘
                              │ reabrir (com justificativa)
                              ▼
                      ┌───────────────┐
                      │EM_INVESTIGACAO│
                      └───────────────┘
```

### Regras de Transição:
- **`PENDENTE` $\rightarrow$ `EM_INVESTIGACAO`:**
  - Acionada via `POST /api/inventarios/{id}/investigacoes/{invId}/iniciar`.
  - Registra o responsável atribuído e, opcionalmente, a causa suspeita inicial e justificativa.
- **`EM_INVESTIGACAO` $\leftrightarrow$ `AGUARDANDO_EVIDENCIA`:**
  - Usada quando a apuração requer fotos do estoque, conferência de notas fiscais ou validação documental externa.
  - A adição de uma nova evidência enquanto em `AGUARDANDO_EVIDENCIA` transita automaticamente de volta para `EM_INVESTIGACAO`.
- **`EM_INVESTIGACAO` / `AGUARDANDO_EVIDENCIA` $\rightarrow$ `RESOLVIDA`:**
  - Acionada via `POST /api/inventarios/{id}/investigacoes/{invId}/resolver`.
  - Exige **causa confirmada** (diferente de `NAO_IDENTIFICADA`) e conclusão descritiva (mínimo 5 caracteres).
  - Bloqueia novas adições de evidências ou vínculos sem reabertura.
- **`EM_INVESTIGACAO` / `AGUARDANDO_EVIDENCIA` $\rightarrow$ `SEM_CAUSA_IDENTIFICADA`:**
  - Acionada via `POST /api/inventarios/{id}/investigacoes/{invId}/sem-causa`.
  - Define automaticamente a causa confirmada como `NAO_IDENTIFICADA`.
  - Exige conclusão/justificativa detalhada (mínimo 10 caracteres).
- **`RESOLVIDA` / `SEM_CAUSA_IDENTIFICADA` $\rightarrow$ `EM_INVESTIGACAO` (Reabertura):**
  - Acionada via `POST /api/inventarios/{id}/investigacoes/{invId}/reabrir`.
  - Exige justificativa formal (mínimo 5 caracteres), limpa `resolvido_em`/`resolvido_por` e registra o evento `REABERTA` na linha do tempo.

---

## 4. Catálogo de Causas

As causas suspeitas e confirmadas utilizam o mesmo domínio padronizado:

| Código | Descrição Operacional |
| :--- | :--- |
| `ERRO_CONTAGEM` | Falha humana na contagem ou bipagem física. |
| `INVERSAO_PRODUTO` | Troca de mercadoria por produto fisicamente semelhante (SKU invertido). |
| `INVERSAO_VOLTAGEM` | Inversão entre versões 110V e 220V do mesmo modelo. |
| `AVARIA` | Produto danificado fisicamente não baixado no sistema. |
| `ASSISTENCIA` | Mercadoria enviada para assistência técnica / conserto sem lançamento. |
| `ERRO_SEPARACAO` | Expedição ou separação incorreta para pedido de venda. |
| `MERCADORIA_CLIENTE` | Produto retido ou devolvido por cliente presente no estoque. |
| `MOVIMENTACAO` | Transferência interna entre depósitos/lojas pendente de registro. |
| `FISCAL_NF` | Divergência em entrada/saída de nota fiscal. |
| `NAO_LOCALIZADO` | Produto não localizado no endereço de estoque. |
| `OUTRO` | Outra causa operacional devidamente justificada. |
| `NAO_IDENTIFICADA` | Causa desconhecida após investigação completa (exclusiva de `SEM_CAUSA_IDENTIFICADA`). |

---

## 5. Evidências e Vínculos entre Produtos

### Evidências (`evidencias_investigacao`):
Permite anexar elementos de convicção à investigação:
- **Tipos Suportados:** `OBSERVACAO`, `FOTO`, `DOCUMENTO`, `NOTA_FISCAL`, `CONTAGEM`, `PRODUTO_RELACIONADO`, `OUTRO`.
- Cada evidência armazena: tipo, descrição textual, referência externa opcional (número de NF, link de anexo), autor e timestamp.

### Vínculos entre Produtos (`investigacao_vinculos`):
Trata cenários clássicos de inversão de estoque (ex: Sobra de 2 unidades no `SKU-A` e Falta de 2 unidades no `SKU-B`):
- **Tipos de Vínculo:**
  - `POSSIVEL_INVERSAO`: Produtos com características semelhantes trocados.
  - `POSSIVEL_VOLTAGEM`: Inversão entre 110V e 220V.
  - `MESMO_PRODUTO`: Produto localizado sob outra codificação ou localização.
  - `MOVIMENTACAO_RELACIONADA`: Movimentação física cruzada.
  - `OUTRO`: Outro vínculo justificado.
- **Validações de Integridade:**
  - O produto vinculado deve pertencer ao mesmo inventário e mesma filial.
  - É proibido vincular um item a si mesmo (`self-linking`).
  - Unicidade por par `(investigacao_id, inventario_item_relacionado_id)`.

---

## 6. Isolamento Multi-Filial e Segurança

- Todas as tabelas de investigação respeitam a coluna `filial_id`.
- Operadores autenticados na Filial `281` não conseguem listar, visualizar, abrir, anexar evidências, vincular produtos ou resolver investigações da Filial `282`.
- O isolamento é validado em nível de banco de dados (`JOIN filiais`) e nos controladores da aplicação (`requireBranchAndInventory`).
- Tentativas de transição de estado com concorrência conflitante utilizam controle de versão otimista (`version`).

---

## 7. Rotas REST da API

As rotas são servidas sob o prefixo `/api/inventarios/{id}/investigacoes` (e o alias `/api/inventory/sessions/{id}/investigations`):

| Método | Endpoint | Descrição |
| :--- | :--- | :--- |
| `GET` | `/api/inventarios/{id}/investigacoes?branchCode={cod}&status={st}` | Lista resumos com contadores e filtros |
| `GET` | `/api/inventarios/{id}/investigacoes/{invId}?branchCode={cod}` | Detalhes completos (origem, evidências, vínculos, timeline) |
| `POST` | `/api/inventarios/{id}/investigacoes?branchCode={cod}` | Abre investigação para item divergente |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/iniciar?branchCode={cod}` | Inicia investigação (`PENDENTE` $\rightarrow$ `EM_INVESTIGACAO`) |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/suspeita?branchCode={cod}` | Atualiza causa suspeita |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/status?branchCode={cod}` | Transição controlada de status |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/evidencias?branchCode={cod}` | Adiciona evidência e registra evento |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/vinculos?branchCode={cod}` | Vincula outro produto do inventário |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/resolver?branchCode={cod}` | Conclui com causa confirmada |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/sem-causa?branchCode={cod}` | Conclui sem causa identificada |
| `POST` | `/api/inventarios/{id}/investigacoes/{invId}/reabrir?branchCode={cod}` | Reabre investigação finalizada |
