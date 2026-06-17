# Deploy

## Neon PostgreSQL

1. Crie uma conta em https://neon.tech.
2. Crie um banco PostgreSQL.
3. Copie a connection string no formato:

```text
postgresql://usuario:senha@host/neondb?sslmode=require
```

Não use comandos como `psql`, `npx neonctl` ou texto adicional na variável.

## Render

1. Crie um Web Service.
2. Conecte o repositório do GitHub.
3. Use o `Dockerfile`.
4. Configure as variáveis:

```text
DATABASE_URL=postgresql://...
MMCHECK_ADMIN_PASSWORD=...
```

5. Faça deploy.

## Verificação

Depois do deploy:

```text
/api/health
/api/version
```

O health deve retornar `status: ok` e a identificação do banco.
