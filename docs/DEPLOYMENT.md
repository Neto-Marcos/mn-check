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

5. Gere e valide um backup antes do deploy.
6. Faça deploy primeiro em homologação e execute `docs/HOMOLOGATION.md`.
7. Promova para produção somente com a homologação aprovada.

## Backup

É necessário ter `pg_dump` e `pg_restore` do PostgreSQL instalados:

```powershell
.\scripts\backup-postgres.ps1 -DatabaseUrl $env:DATABASE_URL
```

O script gera dump no formato custom, SHA-256 e manifesto. Guarde o diretório fora do repositório.

Se os executáveis não estiverem no `PATH`, informe a pasta `bin` do PostgreSQL:

```powershell
.\scripts\backup-postgres.ps1 -DatabaseUrl $env:DATABASE_URL -PostgresBin "C:\Program Files\PostgreSQL\16\bin"
```

Para verificar o conteúdo sem restaurar:

```powershell
.\scripts\verify-backup.ps1 -BackupDirectory caminho\do\backup
```

Para cumprir a validação de restauração, use exclusivamente um banco descartável vazio:

```powershell
.\scripts\verify-backup.ps1 -BackupDirectory caminho\do\backup -RestoreDatabaseUrl $env:STAGING_RESTORE_DATABASE_URL
```

## Migração 3.0

- As migrações são aditivas e executadas na inicialização.
- `filiais`, `produtos`, saldos e operações empresariais começam limpos.
- O histórico antigo permanece no backup e nas tabelas legadas.
- O saldo empresarial inicial é publicado por contagem física aprovada.
- Não importe movimentos antigos para o novo livro.

## Rollback

1. Interrompa novas operações.
2. Promova o deployment/tag anterior no Railway.
3. As tabelas 3.0 podem permanecer no banco; a versão 2.3.0 não as utiliza.
4. Se for indispensável restaurar dados, use um banco vazio de recuperação e valide o dump antes de qualquer troca de URL.
5. Nunca restaure diretamente sobre produção sem uma janela aprovada.

## Verificacao

Depois do deploy:

```text
/api/health
/api/version
```

O health deve retornar `status: ok` e a identificacao do banco.

## Vercel

Use a Vercel apenas para portfolio ou frontend estatico. O backend Java do MN Check deve ficar no Railway.
