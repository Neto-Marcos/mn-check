# CEGO R2 → investigação → fechamento

Base: `d9cd877c2dfdaa5d081e73e4ef1d81712e1108f7`.

## Reprodução e contrato

O teste integrado reproduziu o P0 antes da correção em PostgreSQL 17 local,
com saldo 1, R1 zero e R2 zero: apuração interna `DIVERGENCIA_CONFIRMADA`,
diferença -1, DTO público `CONTADO` e nenhuma tarefa operacional disponível.

`AuditItem.podeInvestigar` é uma capability calculada no servidor com a apuração
interna, rodada finalizada, inventário mutável e ausência de investigação para
a apuração. Em CEGO ela é oferecida somente após R2. Inclui pendências não
contadas, sem codificar a causa, direção ou magnitude da divergência. Depois
de criar a investigação, a capability desaparece; o acompanhamento ocorre
na lista de investigações existente. O POST continua validando filial,
inventário, apuração, conformidade e duplicidade no servidor.

O frontend usa essa capability para a ação e, em CEGO R2, para a aba
“Investigações pendentes”. Não reconstrói elegibilidade com `estado` público.
Saldo, diferença, estado interno e agregados continuam sanitizados pela
InventorySecurityPolicy, que não foi alterada. A tarefa operacional é a
informação mínima autorizada para executar o workflow; não é um novo estado
de apuração pública. Não houve alteração de schema ou de V1–V7.

## Auditoria direcionada

- R2: CEGO já envia pedido sem IDs; `createRecountRound` escolhe o escopo
  interno. Seleção de estados no frontend está restrita ao modo NORMAL.
- Investigação: corrigidas a aba e a ação baseadas no estado sanitizado.
  A revisão também encontrou `estadoOriginal` e `diferenca` no evento CRIADA.
  Esses campos são removidos somente da resposta protegida; o evento interno
  e o histórico após ENCERRADO são preservados.
- Fechamento: frontend já usa `validation.podeFechar`; validação e execução
  normal consultam internamente apurações e investigações. Não houve mudança
  nessa regra. A regressão exige bloqueio antes/durante a investigação e
  liberação somente após conclusão.
- Histórico/resultado: `estadoFinal` filtra apenas o resultado imutável depois
  de ENCERRADO, quando a política permite consulta completa. Não decide workflow.
- Contagem: `CONTADO`/`NAO_CONTADO` controla progresso e correção de quantidade;
  são estados operacionais permitidos e preservam zero explícito.
- Relatório: filtros sensíveis são validados server-side em contexto protegido;
  não controlam transições. PDF/XLSX não foram alterados.
- PWA: apenas identificação do cache mudou para entregar a correção aos clientes.

## Regressões adicionadas

Integração real CEGO e NORMAL, múltiplos SKUs, item conforme, zero explícito,
quantidade positiva, R2 mista ou com dois itens pendentes, leitura após F5,
diferença interna -1, capability sem campos sensíveis, isolamento de filial,
duplicidade, investigação aberta/concluída, bloqueio e fechamento normal,
histórico e preservação dos eventos internos. Testes de frontend exercitam o
componente real com DTO sanitizado e verificam a ação e seu payload.

Os testes usam banco descartável; não usam o banco de produção ou staging.
Validação local/CI e smoke de produção devem ser registrados no relatório da
execução; este documento não declara produção liberada.
