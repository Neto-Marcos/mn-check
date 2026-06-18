# Deploy com Neon e Render

## 1. Neon

Crie o projeto em [neon.tech](https://neon.tech/), abra **Connection Details**, escolha a conexÃ£o agrupada e copie a URL PostgreSQL com `sslmode=require`.

## 2. Render

No serviÃ§o `mm-check`, abra **Environment** e configure:

```text
DATABASE_URL=postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require
MMCHECK_ADMIN_PASSWORD=uma-senha-segura
GEMINI_API_KEY=opcional-apenas-para-mapas
```

Remova `MMCHECK_DB_PATH` e `MMCHECK_UPLOAD_DIR` caso ainda existam. O sistema nÃ£o usa armazenamento local.

## 3. Deploy

Para seguir o fluxo normal do GitHub, deixe **Settings > Auto-Deploy** ligado no Render. O `render.yaml` tambÃƒÂ©m declara `autoDeploy: true`.

Execute:

```text
Manual Deploy > Deploy latest commit
```

Depois valide:

```text
https://mm-check.onrender.com/api/version
https://mm-check.onrender.com/api/health
```

VersÃ£o esperada:

```json
{"app":"MN - Check","version":"1.8.6"}
```

O primeiro boot cria automaticamente todas as tabelas. Uma falha de conexÃ£o impede a inicializaÃ§Ã£o, evitando perda silenciosa de dados.

## CÃ¢mera no celular

O navegador exige HTTPS para `getUserMedia`. O endereÃ§o pÃºblico do Render jÃ¡ usa HTTPS, portanto basta autorizar a cÃ¢mera no Chrome Android ou Safari do iPhone.

O serviÃ§o precisa expor somente a porta pÃºblica definida por `PORT`. O nÃºcleo de compatibilidade roda internamente na porta `4174`.
