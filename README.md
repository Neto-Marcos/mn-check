# MN - Check

Controle de separação, conferência e estoque.

Versão atual: **1.5.0**

## Persistência

O backend exige PostgreSQL e não utiliza banco em memória, SQLite ou arquivos JSON/TXT locais.

Os dados de saldo e contagem usam as tabelas relacionais:

- `importacoes_saldo`;
- `saldos`;
- `contagens`;
- `itens_contagem`.

Usuários, mapas, notificações e anexos também permanecem no PostgreSQL. As tabelas são criadas automaticamente com `CREATE TABLE IF NOT EXISTS`.

## Criar o banco no Neon

1. Acesse [Neon](https://neon.tech/) e crie uma conta.
2. Clique em **New project**.
3. Escolha um nome, por exemplo `mn-check`.
4. Mantenha PostgreSQL e uma região próxima do Render.
5. Abra **Dashboard > Connection Details**.
6. Selecione a conexão `Pooled connection`.
7. Copie a URL completa.

Formato esperado:

```text
postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require
```

Não publique essa URL no GitHub. Ela contém a senha do banco.

## Configurar no Render

1. Abra o serviço `mm-check` no Render.
2. Entre em **Environment**.
3. Crie ou substitua `DATABASE_URL` pela URL copiada do Neon.
4. Mantenha `MMCHECK_ADMIN_PASSWORD` configurada.
5. Salve as variáveis.
6. Execute **Manual Deploy > Deploy latest commit**.
7. Confira `/api/health`; o campo `database` deve indicar PostgreSQL.

O arquivo `render.yaml` declara `DATABASE_URL` como variável secreta (`sync: false`).

## Rodar localmente

Requisitos:

- Java 21;
- Maven 3.9+;
- um banco PostgreSQL Neon acessível.

PowerShell:

```powershell
$env:DATABASE_URL="postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require"
$env:MMCHECK_ADMIN_PASSWORD="senha-inicial-segura"
mvn test
mvn package
java -jar target/mn-check.jar
```

Abra:

```text
http://127.0.0.1:4173/
```

Sem `DATABASE_URL`, o servidor interrompe a inicialização. Isso evita executar acidentalmente com dados descartáveis.

## Docker

```bash
docker build -t mn-check .
docker run --rm -p 4173:4173 \
  -e DATABASE_URL="postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require" \
  -e MMCHECK_ADMIN_PASSWORD="senha-inicial-segura" \
  mn-check
```

## Importação de saldo

1. Entre como administrador ou conferente de estoque.
2. Abra **Contagem**.
3. Selecione o PDF de saldo.
4. Aguarde a leitura e confira os indicadores.

O Apache PDFBox percorre todas as páginas. O parser identifica Produto, Grade X, Grade Y e Saldo e monta:

```text
produto-gradeX.gradeY
```

Exemplo:

```text
74683-1.2
```

Linhas vazias, cabeçalhos, totais, rodapés e `9999999` são ignorados. Duplicidades são contabilizadas e saldos conflitantes bloqueiam a importação. A IA não participa da leitura principal.

Após a validação, a importação e seus saldos são gravados em uma única transação PostgreSQL.

Log esperado:

```text
SALDO_PDF arquivo="saldo.pdf" paginas=5 skus=242 linhas_ignoradas=27 duplicados=0 conflitos=0 duracao_ms=259
SALDO_POSTGRES importacao_id=1 arquivo="saldo.pdf" skus=242 atualizado_em=2026-06-10T...
```

## Histórico

`GET /api/historico` consulta importações e contagens diretamente no PostgreSQL. Os registros continuam disponíveis após reinício ou novo deploy do serviço.

## Impressão A4

1. Abra **Contagem**.
2. Preencha as quantidades.
3. Clique em **Atualizar contagem**.
4. Clique em **Imprimir contagem**.

O relatório mantém PDF de origem, datas, SKU, saldo, contado, diferença, status, totais e assinaturas. O navegador permite imprimir ou salvar como PDF.

## Endpoints

| Método | Endpoint | Função |
|---|---|---|
| `GET` | `/api/health` | Saúde, versão e banco |
| `GET` | `/api/version` | Versão pública |
| `POST` | `/api/login` | Autenticação |
| `GET` | `/api/bootstrap` | Dados permitidos ao usuário |
| `GET` | `/api/saldos` | Última importação e saldo real |
| `POST` | `/api/importar` | Importar PDF e gravar no PostgreSQL |
| `POST` | `/api/contagem` | Gravar contagem e itens |
| `GET` | `/api/historico` | Histórico persistente |

## Testes

O GitHub Actions inicia PostgreSQL 17 e executa:

```bash
mvn test
mvn package
node --check frontend/app.js
docker build -t mn-check-ci .
```

`PostgresDatabaseTest` comprova:

- conexão JDBC;
- criação automática das tabelas;
- gravação da importação;
- gravação da contagem;
- leitura do histórico;
- persistência ao criar uma nova conexão, simulando reinício.

Para uma validação no Neon, execute `mvn test` com a `DATABASE_URL` real configurada.
