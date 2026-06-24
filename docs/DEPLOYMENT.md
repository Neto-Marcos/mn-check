# Deploy

## Neon PostgreSQL

1. Crie uma conta em https://neon.tech.
2. Crie um banco PostgreSQL.
3. Copie a connection string no formato:

```text
postgresql://usuario:senha@host/neondb?sslmode=require
```

Nao use comandos como `psql`, `npx neonctl` ou texto adicional na variavel.

## Railway

1. Crie um projeto no Railway.
2. Conecte o repositorio `Neto-Marcos/mn-check`.
3. O Railway usa `Dockerfile` e `railway.json`.
4. Configure as variaveis:

```text
DATABASE_URL=postgresql://...
MMCHECK_ADMIN_PASSWORD=...
GEMINI_API_KEY=...
GEMINI_MODEL=gemini-2.5-flash
```

5. Faca deploy.

## Verificacao

Depois do deploy:

```text
/api/health
/api/version
```

O health deve retornar `status: ok` e a identificacao do banco.

## Vercel

Use a Vercel apenas para portfolio ou frontend estatico. O backend Java do MN Check deve ficar no Railway.
