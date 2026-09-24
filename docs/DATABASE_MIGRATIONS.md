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

## Próxima migration planejada

A próxima etapa será dividida para evitar mudança ampla:

1. adicionar `filial_id` nullable em `importacoes_saldo`, `estoque_produtos` e `contagens`;
2. avaliar `saldos`: a filial pode ser derivada de `importacoes_saldo`, portanto não deve ser duplicada sem necessidade comprovada;
3. avaliar `itens_contagem`: a filial pode ser derivada de `contagens`, portanto não deve ser duplicada inicialmente;
4. criar a Filial 281 se ausente e fazer backfill em lotes;
5. comparar contagens antes/depois e exigir zero registros sem filial;
6. criar índices e chaves estrangeiras como `NOT VALID`, validar separadamente;
7. somente depois aplicar `NOT NULL`;
8. alterar consultas e unicidades por filial em uma etapa funcional posterior.

Nenhuma dessas alterações faz parte da V1.
