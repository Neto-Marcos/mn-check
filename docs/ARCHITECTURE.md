# Arquitetura do MN Check

O MN Check foi construído como um sistema operacional de logística com backend Java, interface web responsiva e persistência PostgreSQL.

## Camadas Atuais

- **Frontend**: `frontend/app.js`, `frontend/styles.css` e service worker.
- **Backend HTTP**: `backend/src/MmCheckServer.java`.
- **Scanner**: `BarcodeParser`, `BarcodeValidationService` e `ScannerController`.
- **PDF**: `BalancePdfParser`, baseado em Apache PDFBox.
- **Persistência**: `PostgresDatabase`, usando JDBC e migrations automáticas.
- **Banco**: Neon/PostgreSQL.

## Decisões Técnicas

- O parser principal de saldo não depende de IA.
- SKUs duplicados no PDF têm saldo somado.
- Produtos removidos do PDF são marcados como inativos, não apagados.
- Contagens preservam snapshot do saldo usado naquele momento.
- Conferências podem ser salvas, pausadas, retomadas ou canceladas com confirmação.

## Módulo empresarial 3.0

- `EnterpriseController`: contrato HTTP versionado em `/api/v2`.
- `EnterpriseService`: regras transacionais, permissões, estados e idempotência.
- `EnterpriseDatabase`: gateway JDBC, migrações e limites transacionais.
- `NfeXmlParser`: leitura segura de NF-e, com DTD e entidades externas desativadas.
- `enterprise-schema.sql`: entidades normalizadas e índices operacionais.
- `enterprise.js` e `enterprise.css`: interface isolada da contagem 2.3.0.

O legado continua atendendo autenticação, contagem e compatibilidade durante a transição. Novos fluxos não gravam o estado operacional em `mn_check_state`.

As senhas novas são armazenadas com bcrypt (custo 12), sessões expiram após 12 horas de inatividade e tentativas de login são bloqueadas temporariamente após repetição excessiva. Hashes SHA-256 antigos continuam aceitos somente para migração de usuários já existentes; qualquer troca de senha os converte para bcrypt.

## Invariantes de estoque

- `disponivel + reservado + quarentena <= fisico`.
- Nenhum componente altera `saldos_estoque` sem inserir um movimento na mesma transação.
- Toda operação mutável exige uma chave registrada em `requisicoes_idempotentes`.
- Estornos apontam para o movimento original e aplicam deltas inversos.
- Expedição somente ocorre após separação e reconferência completas.
- Divergências não entram silenciosamente no estoque regular.

## Garantias verificadas

`EnterpriseServiceIntegrationTest` inicializa um PostgreSQL 14 descartável e percorre o fluxo completo. Além das regras funcionais, o teste disputa a mesma disponibilidade com duas transações concorrentes, repete chaves de idempotência, valida isolamento entre filiais e exige reconciliação integral ao final.

## Compatibilidade de atualização

O backend mantém `allowedViews` compatível com o frontend 2.3.0 em cache. O frontend 3.0 calcula a nova navegação a partir da função do usuário. Assim, o service worker anterior consegue atualizar sem quebrar a tela antes do recarregamento.
