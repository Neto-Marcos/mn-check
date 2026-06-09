# Deploy do MN - Check

## O que vai no GitHub

Use o GitHub para guardar e versionar o código:

- `backend/`
- `frontend/`
- `database/`
- `Dockerfile`
- `.github/workflows/ci.yml`

Não use GitHub como banco de dados operacional.

## Banco de dados

O app ainda roda com JSON local:

```text
data/java-db.json
```

Para produção, use PostgreSQL. O arquivo inicial de tabelas está em:

```text
database/postgres-schema.sql
```

Opções recomendadas:

- Supabase PostgreSQL
- Neon PostgreSQL
- Railway PostgreSQL
- Render PostgreSQL
- PostgreSQL instalado em servidor privado

## Servidor privado

1. Instale Java 21 ou superior.
2. Copie o projeto para o servidor.
3. Compile:

```bash
javac -encoding UTF-8 -d backend/out backend/src/MmCheckServer.java
```

4. Rode:

```bash
PORT=4173 java -cp backend/out MmCheckServer
```

5. Configure firewall liberando a porta `4173` somente para a rede desejada.

## Servidor na internet com Docker

Build:

```bash
docker build -t mm-check .
```

Run:

```bash
docker run -p 4173:4173 -v mm-check-data:/app/data mm-check
```

Depois acesse:

```text
http://IP_DO_SERVIDOR:4173/
```

Para internet pública, coloque HTTPS com Nginx, Caddy, Cloudflare Tunnel ou proxy do provedor.

## Próxima etapa para banco real

O próximo desenvolvimento é trocar o repositório JSON por PostgreSQL via JDBC.

Variáveis que já existem para ambiente:

```text
PORT
MMCHECK_DB_PATH
MMCHECK_UPLOAD_DIR
```

Quando migrar para PostgreSQL, adicionar:

```text
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
```
