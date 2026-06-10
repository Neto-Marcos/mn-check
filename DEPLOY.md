# Deploy do MN - Check

## Render com PostgreSQL

O `render.yaml` define o serviço web e o PostgreSQL. Para criar uma nova instalação:

1. Conecte o repositório ao Render usando **New Blueprint Instance**.
2. Confirme a criação de `mm-check` e `mn-check-db`.
3. Configure `MMCHECK_ADMIN_PASSWORD`.
4. Configure `GEMINI_API_KEY`.
5. Aguarde o health check em `/api/health`.

O Render fornece `DATABASE_URL` automaticamente por `fromDatabase`.

O plano `free` do PostgreSQL do Render expira após 30 dias. Para operação contínua, altere o plano no `render.yaml` ou configure no serviço uma `DATABASE_URL` de um PostgreSQL externo permanente.

## Atualizações

Cada push na branch conectada inicia um novo deploy. A versão pública pode ser conferida em:

```text
https://SEU-SERVICO.onrender.com/api/version
```

Resposta esperada:

```json
{"app":"MN - Check","version":"1.5.0"}
```

## Banco já existente

Se o serviço web já existe sem banco:

1. Crie um PostgreSQL no Render.
2. Copie a **Internal Database URL**.
3. Adicione a variável `DATABASE_URL` ao serviço web.
4. Faça um novo deploy.

As tabelas são criadas automaticamente. Quando um arquivo JSON local ainda estiver disponível e o PostgreSQL estiver vazio, o backend tenta migrá-lo na inicialização.

## Observação sobre arquivos

Mapas, usuários, saldos, contagens, notificações, histórico, PDFs e evidências ficam no PostgreSQL. O diretório local de uploads é usado somente quando `DATABASE_URL` não está configurada.
