# Deploy com Neon e Railway

## 1. Neon

Crie o banco em https://neon.tech e copie a connection string PostgreSQL com `sslmode=require`.

Exemplo:

```text
postgresql://usuario:senha@host/neondb?sslmode=require
```

Nao copie comandos `psql`, `npx neonctl` ou textos adicionais.

## 2. Railway

No Railway, crie um projeto a partir do repositorio:

```text
Neto-Marcos/mn-check
```

O projeto usa:

- `Dockerfile`
- `railway.json`
- healthcheck em `/api/health`

## 3. Variaveis

Configure em **Variables**:

```text
DATABASE_URL=postgresql://usuario:senha@host/neondb?sslmode=require
MMCHECK_ADMIN_PASSWORD=uma-senha-segura
GEMINI_API_KEY=opcional-apenas-para-mapas
GEMINI_MODEL=gemini-2.5-flash
```

Nao configure `PORT` manualmente. O container usa `8080` por padrao e tambem respeita a variavel `PORT` quando o Railway injeta.

Ao gerar dominio em **Networking**, use a porta:

```text
8080
```

## 4. Validacao

Depois do deploy, valide:

```text
https://seu-app.up.railway.app/api/version
https://seu-app.up.railway.app/api/health
```

O primeiro boot cria automaticamente as tabelas no PostgreSQL. Se `DATABASE_URL` estiver incorreta, o app falha ao iniciar para evitar perda silenciosa de dados.

## 5. Vercel

A Vercel fica reservada para o portfolio ou frontend estatico. O backend Java do MN Check deve ficar no Railway.
