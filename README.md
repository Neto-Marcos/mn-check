# MN - Check

MVP empresarial local com backend em Java e frontend em React para controle de mapas de carga, separação, expedição, contagem, usuários e histórico operacional.

Versão atual: **1.3.0**

[![Publicar no Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/Neto-Marcos/mm-check)

## Publicação gratuita

1. Clique em **Publicar no Render**.
2. Entre ou crie uma conta no Render usando o GitHub.
3. Informe uma senha segura no campo `MMCHECK_ADMIN_PASSWORD`.
4. Confirme a criação do serviço.

O Render cria um endereço HTTPS para o MN - Check. No plano gratuito, o primeiro acesso após um período parado pode levar cerca de um minuto.

## Leitura de documentos com IA

O MN - Check usa o Gemini 2.5 Flash para extrair dados de PDFs e fotos de mapas de carga e também dos relatórios de saldo com várias folhas. Crie uma chave gratuita no [Google AI Studio](https://aistudio.google.com/apikey) e adicione no Render:

```text
GEMINI_API_KEY=sua-chave
GEMINI_MODEL=gemini-2.5-flash
```

A chave deve existir somente nas variáveis de ambiente do servidor. Nunca coloque a chave no frontend ou em um arquivo enviado ao GitHub.

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

Na conferência de expedição, o MN - Check lê códigos Code 128 pela câmera, aceita digitação manual e registra foto como evidência.

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
