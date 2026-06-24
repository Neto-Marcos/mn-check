# MN Check - Sistema de separacao, conferencia e contagem

![Java](https://img.shields.io/badge/Java-21-ef4444?style=for-the-badge)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-16a34a?style=for-the-badge)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL%2FNeon-Persistence-2563eb?style=for-the-badge)
![PDFBox](https://img.shields.io/badge/PDFBox-PDF%20Parser-f97316?style=for-the-badge)
![Railway](https://img.shields.io/badge/Railway-Deploy-111827?style=for-the-badge)

O **MN Check** e um sistema operacional para mapas de carga, separacao por coletor, conferencia de expedicao e contagem de estoque. Ele foi criado para reduzir erro humano em SKU, cor, voltagem, quantidade e saldo em um fluxo logistico real.

## Principais recursos

- Leitura de separacao por coletor USB/Bluetooth ou digitacao manual.
- Conferencia de expedicao por codigo de barras CODE 128.
- Validacao de SKU, cor e voltagem antes de aprovar a leitura.
- Upload de mapa por PDF ou multiplas imagens.
- Revisao dos itens lidos por IA antes de salvar o mapa.
- Importacao de saldo por PDF usando Apache PDFBox.
- Historico operacional, divergencias e notificacoes para administradores.
- Persistencia em PostgreSQL/Neon.
- Interface responsiva para desktop, tablet e celular.
- Service worker para cache da interface e uso recente no aparelho.

## Fluxo operacional

1. O usuario de separacao cria um mapa informando numero do mapa, pedidos e arquivos.
2. A IA le PDF/imagens e gera um rascunho editavel.
3. A separacao le cada unidade com o coletor.
4. Quando todas as unidades estao lidas, o mapa e enviado para conferencia.
5. A expedicao confere novamente por codigo de barras.
6. Divergencias ficam registradas para correcao e auditoria.

## Tecnologias

- Java 21
- Spring Boot
- JDBC
- PostgreSQL / Neon
- Apache PDFBox
- HTML, CSS e JavaScript
- React via bundle local
- Docker
- Railway

## Estrutura

```text
MM check/
├── backend/
│   └── src/                 # servidor, scanner, parser PDF, PostgreSQL e regras
├── database/
│   └── postgres-schema.sql  # schema do banco
├── docs/
│   ├── ARCHITECTURE.md
│   └── DEPLOYMENT.md
├── frontend/
│   ├── app.js               # interface React sem build externo
│   ├── mapas.js             # leitura local de arquivos de mapa
│   ├── scanner.js           # normalizacao e validacao local de codigos
│   ├── state.js             # estado, constantes e configuracoes
│   ├── styles.css           # design system responsivo
│   └── sw.js                # cache offline
├── screenshots/
├── Dockerfile
├── pom.xml
└── env.example
```

## Como rodar localmente

1. Clone o projeto.
2. Copie `env.example` para `.env` ou configure as variaveis no terminal.
3. Configure `DATABASE_URL` com a connection string do Neon/PostgreSQL.
4. Configure `MMCHECK_ADMIN_PASSWORD`.
5. Opcionalmente configure `GEMINI_API_KEY` para leitura de mapas por IA.
6. Execute:

```bash
mvn clean package
java -jar target/mn-check-1.9.6.jar
```

Acesse:

```text
http://localhost:4173
```

## Variaveis de ambiente

```text
DATABASE_URL=postgresql://usuario:senha@host/neondb?sslmode=require
MMCHECK_ADMIN_PASSWORD=senha-inicial-do-admin
GEMINI_API_KEY=sua-chave-do-gemini
GEMINI_MODEL=gemini-2.5-flash
PORT=4173
```

## Deploy no Railway

1. Crie um projeto no Railway usando o repositorio `Neto-Marcos/mn-check`.
2. O Railway usa `Dockerfile` e `railway.json`.
3. Configure `DATABASE_URL` e `MMCHECK_ADMIN_PASSWORD`.
4. Opcionalmente configure `GEMINI_API_KEY` e `GEMINI_MODEL`.
5. Confira o healthcheck em `/api/health`.

Detalhes adicionais ficam em [`RAILWAY.md`](RAILWAY.md).

## Vercel

A Vercel fica reservada para o portfolio ou frontend estatico. O backend Java do MN Check deve ficar no Railway.

## Endpoints principais

| Metodo | Rota | Uso |
|---|---|---|
| `GET` | `/api/health` | status da aplicacao e banco |
| `GET` | `/api/version` | versao atual |
| `GET` | `/api/bootstrap` | dados iniciais da tela |
| `POST` | `/api/maps/analyze` | analisar um mapa por PDF/imagens |
| `POST` | `/api/maps/confirm` | salvar o mapa revisado |
| `POST` | `/api/scanner/validate` | validar leitura do coletor |
| `POST` | `/api/importar` | importar PDF de saldo |
| `POST` | `/api/saldos/produto` | adicionar produto manualmente ao saldo |
| `POST` | `/api/contagem` | salvar contagem fisica |
| `GET` | `/api/historico` | historico operacional |

## Testes

```bash
mvn test
```

No Windows, se o Maven estiver instalado no perfil do usuario:

```powershell
.\scripts\test-local.ps1
```

Os testes cobrem parser de PDF, normalizacao de SKU, validacao de codigo de barras e persistencia quando `DATABASE_URL` esta disponivel.

## Autor

**Marcos Neto**

- GitHub: [Neto-Marcos](https://github.com/Neto-Marcos)

## Licenca

MIT. Veja [`LICENSE`](LICENSE).
