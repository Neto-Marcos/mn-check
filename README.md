# MN - Check

Sistema operacional para separação, conferência, contagem de estoque e histórico.

Versão atual: **1.5.0**

## Produção no Render

O projeto usa PostgreSQL quando `DATABASE_URL` está configurada. O arquivo `render.yaml` cria:

- o serviço web `mm-check`;
- o banco PostgreSQL `mn-check-db`;
- a variável `DATABASE_URL` ligada automaticamente ao banco.

No primeiro deploy, informe:

```text
MMCHECK_ADMIN_PASSWORD=senha-inicial-segura
GEMINI_API_KEY=chave-do-google-ai-studio
```

O backend executa `CREATE TABLE IF NOT EXISTS` automaticamente. Os dados deixam de depender do disco efêmero do Render e continuam disponíveis depois de reinícios e novos deploys.

> O PostgreSQL gratuito do Render expira após 30 dias. Para retenção permanente, atualize o banco para um plano pago ou use uma `DATABASE_URL` de outro provedor PostgreSQL persistente.

## Variáveis de ambiente

```text
PORT=4173
DATABASE_URL=postgresql://usuario:senha@host:5432/mn_check
MMCHECK_ADMIN_PASSWORD=senha-inicial-segura
GEMINI_API_KEY=chave-do-google-ai-studio
GEMINI_MODEL=gemini-2.5-flash
```

Sem `DATABASE_URL`, o sistema usa `data/java-db.json` apenas para desenvolvimento local.

## Como rodar localmente

### Com Docker

```bash
docker build -t mn-check .
docker run --rm -p 4173:4173 \
  -e MMCHECK_ADMIN_PASSWORD=sua-senha \
  -e GEMINI_API_KEY=sua-chave \
  -e DATABASE_URL=postgresql://usuario:senha@host:5432/mn_check \
  mn-check
```

### Sem PostgreSQL

```bash
javac -encoding UTF-8 -d backend/out backend/src/MmCheckServer.java
java -cp backend/out MmCheckServer
```

Defina `MMCHECK_ADMIN_PASSWORD` antes da primeira execução. O endereço local é:

```text
http://127.0.0.1:4173/
```

## Importação de saldo

1. Entre como administrador ou conferente de estoque.
2. Abra **Contagem**.
3. Clique em **Selecionar PDF de saldo**.
4. Selecione o relatório completo.
5. Confira arquivo, data, quantidade de SKUs e possíveis avisos.

O Gemini percorre o PDF, extrai Produto, Grade X, Grade Y e Saldo e monta o SKU no formato:

```text
produto-gradeX.gradeY
```

Linhas inválidas e o código `9999999` são ignorados. SKUs duplicados iguais são consolidados com aviso. Saldos conflitantes interrompem a importação.

## Testar impressão

1. Abra **Contagem**.
2. Preencha as quantidades contadas.
3. Clique em **Imprimir contagem**.
4. Na janela do navegador, escolha uma impressora ou **Salvar como PDF**.

O relatório A4 mostra origem, datas, SKU, sistema, contado, diferença, resultado, totais e assinaturas.

## Endpoints

| Método | Endpoint | Função |
|---|---|---|
| `GET` | `/api/health` | Saúde, versão e tipo de banco |
| `GET` | `/api/version` | Versão pública |
| `POST` | `/api/login` | Autenticação |
| `GET` | `/api/bootstrap` | Dados permitidos para o usuário |
| `GET` | `/api/saldos` | Saldos e metadados da importação |
| `POST` | `/api/importar` | Importação do PDF de saldo |
| `POST` | `/api/contagem` | Salvar contagem física |
| `GET` | `/api/historico` | Histórico operacional persistido |

As rotas antigas de contagem foram mantidas temporariamente por compatibilidade.

## Persistência

As tabelas usadas são:

- `mn_check_state`: estado atual completo;
- `mn_check_state_history`: últimos 500 snapshots para recuperação.
- `mn_check_files`: PDFs, imagens e evidências enviadas.

O esquema também está em `database/postgres-schema.sql`.

## Validações

```bash
node --check frontend/app.js
javac -encoding UTF-8 -d backend/out backend/src/MmCheckServer.java
docker build -t mn-check .
```
