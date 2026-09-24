# Estratégia de migrations do MN-Check

## Transição Flyway e schema legado

O Flyway executa antes do servidor legado. A ordem de inicialização é:

1. o Spring Boot configura a conexão a partir de `DATABASE_URL`;
2. o Flyway valida e aplica migrations versionadas;
3. o contexto Spring termina de iniciar;
4. o `MmCheckServer` é iniciado;
5. temporariamente, `PostgresDatabase.migrate()` e o armazenamento JSONB garantem as tabelas legadas.

Essa ordem impede Flyway e o código legado de criarem o schema simultaneamente. O código legado não deve receber novas alterações estruturais que pertençam ao modelo 3.0. Ele será reduzido migration por migration, depois que cada estrutura estiver sob responsabilidade exclusiva do Flyway.

## Baseline

- `baselineVersion`: `0`.
- `baselineOnMigrate`: `true`.
- `validateOnMigrate`: `true`.
- migrations: `classpath:db/migration`.

Em banco existente e sem `flyway_schema_history`, o Flyway registra um baseline na versão 0 e aplica `V1`. Em banco vazio, aplica `V1` diretamente. Assim, a primeira migration roda nos dois casos.

## Primeira migration

`V1__create_filiais.sql` cria `filiais`, aplica unicidade ao código e insere a Filial 281 com `ON CONFLICT (codigo) DO NOTHING`. Ela não altera tabelas operacionais existentes.

## PostgreSQL de testes

O GitHub Actions usa um service container PostgreSQL 17 descartável. Testes que alteram banco exigem simultaneamente:

- `DATABASE_URL` apontando para o banco descartável;
- `MN_CHECK_ALLOW_DATABASE_TESTS=true`.

Sem a autorização explícita, os testes são ignorados. Essa trava reduz o risco de execução acidental contra produção. Localmente, pode-se usar PostgreSQL ou Docker próprios com um banco exclusivamente de teste.

## Rollback operacional

As migrations são roll-forward. Antes de produção, deve-se gerar backup e validar a migration em uma cópia. Se a inicialização falhar, o rollback operacional é voltar ao artefato anterior; a tabela `filiais` pode permanecer porque não interfere no legado. Não apagar tabela nem editar `flyway_schema_history` automaticamente.

## V2 — contexto de filial no estoque

`V2__add_filial_context_to_inventory.sql` adiciona `filial_id` apenas às raízes
`importacoes_saldo`, `estoque_produtos` e `contagens`. `saldos` herda o contexto da
importação e `itens_contagem` herda o contexto da contagem.

A migration exige a Filial 281, faz o backfill dos registros legados, verifica que
não restaram órfãos, valida as FKs e só então aplica `NOT NULL`. A chave primária
global de `estoque_produtos.sku` é substituída por `(filial_id, sku)` depois de
confirmar que a chave encontrada é exatamente a chave legado esperada.

Nesta primeira versão, cada PDF deve conter exatamente uma filial. O código é
extraído do próprio documento. PDF misto é rejeitado e não é dividido
automaticamente. APIs antigas que não informam `branchCode` usam a compatibilidade
centralizada `PostgresDatabase.LEGACY_BRANCH_CODE` (`281`). Esse fallback não
representa autorização por filial e deverá ser removido quando o contexto do usuário
for implementado.
