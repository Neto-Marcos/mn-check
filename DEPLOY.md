# Deploy com Neon e Render

## 1. Neon

Crie o projeto em [neon.tech](https://neon.tech/), abra **Connection Details**, escolha a conexão agrupada e copie a URL PostgreSQL com `sslmode=require`.

## 2. Render

No serviço `mm-check`, abra **Environment** e configure:

```text
DATABASE_URL=postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require
MMCHECK_ADMIN_PASSWORD=uma-senha-segura
GEMINI_API_KEY=opcional-apenas-para-mapas
```

Remova `MMCHECK_DB_PATH` e `MMCHECK_UPLOAD_DIR` caso ainda existam. O sistema não usa armazenamento local.

## 3. Deploy

Execute:

```text
Manual Deploy > Deploy latest commit
```

Depois valide:

```text
https://mm-check.onrender.com/api/version
https://mm-check.onrender.com/api/health
```

Versão esperada:

```json
{"app":"MN - Check","version":"1.6.3"}
```

O primeiro boot cria automaticamente todas as tabelas. Uma falha de conexão impede a inicialização, evitando perda silenciosa de dados.

## Câmera no celular

O navegador exige HTTPS para `getUserMedia`. O endereço público do Render já usa HTTPS, portanto basta autorizar a câmera no Chrome Android ou Safari do iPhone.

O serviço precisa expor somente a porta pública definida por `PORT`. O núcleo de compatibilidade roda internamente na porta `4174`.
