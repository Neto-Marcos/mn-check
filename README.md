# MM check

MVP empresarial local com backend em Java e frontend em React para controle de mapas de carga, separação, expedição, contagem, usuários, auditoria e histórico.

## Acesso

O app roda no mesmo endereço:

```text
http://127.0.0.1:4173/
```

Admin inicial:

```text
Usuário: Marcos
Senha: definida de forma segura em MMCHECK_ADMIN_PASSWORD
```

## Como rodar

Compile o backend:

```bash
javac -encoding UTF-8 -d backend/out backend/src/MmCheckServer.java
```

Inicie o servidor:

```bash
java -cp backend/out MmCheckServer
```

No Windows, `iniciar-mm-check.bat` abre o servidor e o navegador. Consulte [PENDRIVE.md](PENDRIVE.md) para uma apresentação portátil.

Na conferência de expedição, o MM check lê códigos Code 128 pela câmera, aceita digitação manual e registra foto como evidência.

## GitHub e deploy

O projeto agora tem arquivos para GitHub e servidor:

- `Dockerfile`
- `.github/workflows/ci.yml`
- `DEPLOY.md`
- `database/postgres-schema.sql`
- `.env.example`

Leia [DEPLOY.md](DEPLOY.md) para publicar em servidor privado ou internet.

## Estrutura

- `backend/src/MmCheckServer.java`: backend Java sem dependências externas.
- `frontend/index.html`: entrada do frontend React.
- `frontend/app.js`: aplicação React.
- `frontend/styles.css`: layout visual.
- `data/java-db.json`: banco local em JSON.

## Usuários

O admin pode cadastrar novos logins pela tela **Usuários**.

Funções disponíveis:

- `Administrador`
- `Conferente de separação`
- `Conferente de expedição`
- `Conferente de estoque`

## Observação

Esta versão usa React via CDN no HTML para evitar instalação de dependências durante o MVP. A próxima evolução natural é empacotar o frontend com Vite e trocar o banco JSON por PostgreSQL, SQL Server ou MySQL.
