# MM Check â€” Sistema Inteligente de ConferÃªncia e Contagem

![Java](https://img.shields.io/badge/Java-21-ef4444?style=for-the-badge)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-16a34a?style=for-the-badge)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL%2FNeon-Persistence-2563eb?style=for-the-badge)
![PDFBox](https://img.shields.io/badge/PDFBox-PDF%20Parser-f97316?style=for-the-badge)
![Render](https://img.shields.io/badge/Render-Deploy-111827?style=for-the-badge)

> Projeto desenvolvido para solucionar problemas reais de expediÃ§Ã£o, conferÃªncia e contagem de estoque em ambiente logÃ­stico.

O **MM Check** Ã© um sistema operacional para controle de mapas de carga, separaÃ§Ã£o, reconferÃªncia de expediÃ§Ã£o e contagem de estoque. Ele foi criado a partir de um fluxo real de logÃ­stica industrial, onde erros de SKU, cor, voltagem e divergÃªncia de saldo geram retrabalho, atraso e risco operacional.

## Preview

As capturas profissionais ficam em [`screenshots/`](screenshots/). Gere novas imagens apÃ³s rodar o sistema localmente ou em produÃ§Ã£o.

| Dashboard | ConferÃªncia | Contagem |
|---|---|---|
| `screenshots/dashboard.png` | `screenshots/conferencia.png` | `screenshots/contagem.png` |

## Problema Resolvido

- ConferÃªncia manual sujeita a erro humano.
- Troca de SKU, cor ou voltagem na expediÃ§Ã£o.
- Contagem de estoque sem histÃ³rico confiÃ¡vel.
- ImportaÃ§Ã£o de saldo por PDF com linhas quebradas e colunas desalinhadas.
- Perda de dados apÃ³s reinÃ­cio do servidor.
- Falta de rastreabilidade sobre divergÃªncias e correÃ§Ãµes.

## SoluÃ§Ã£o

O MM Check centraliza o fluxo operacional:

- separador registra itens separados;
- expediÃ§Ã£o reconfere por coletor/bipador;
- estoque importa saldo por PDF e registra contagens;
- administradores acompanham histÃ³rico, divergÃªncias, usuÃ¡rios e relatÃ³rios;
- dados crÃ­ticos persistem em PostgreSQL/Neon.

## Funcionalidades

- ConferÃªncia inteligente por cÃ³digo de barras CODE 128 via coletor USB/Bluetooth.
- ValidaÃ§Ã£o de SKU, cor e voltagem.
- Pausar, retomar ou cancelar conferÃªncias com confirmaÃ§Ã£o.
- Salvamento parcial da conferÃªncia.
- ImportaÃ§Ã£o de saldo por PDF usando Apache PDFBox.
- Parser com leitura de todas as pÃ¡ginas, duplicidade somada e debug detalhado.
- BotÃ£o **Adicionar produto** na contagem para corrigir item nÃ£o lido pelo PDF.
- HistÃ³rico real em PostgreSQL.
- Versionamento de saldo e preservaÃ§Ã£o de contagens anteriores.
- Dashboard operacional com indicadores.
- Modo escuro profissional.
- Interface responsiva para desktop, tablet e celular.
- RelatÃ³rio A4 para imprimir/salvar contagem.
- Service worker para manter interface e operaÃ§Ãµes recentes disponÃ­veis no aparelho.

## Tecnologias

- **Java 21**
- **Spring Boot**
- **JDBC**
- **PostgreSQL / Neon**
- **Apache PDFBox**
- **HTML, CSS e JavaScript**
- **React via bundle local**
- **Render**
- **Docker**

## Arquitetura

```text
MM check/
â”œâ”€â”€ backend/
â”‚   â”œâ”€â”€ src/              # servidor, parser PDF, scanner, PostgreSQL e regras de negÃ³cio
â”‚   â””â”€â”€ test/             # testes de parser, scanner e persistÃªncia
â”œâ”€â”€ frontend/
â”‚   â”œâ”€â”€ app.js            # interface React sem build externo
â”‚   â”œâ”€â”€ styles.css        # design system responsivo
â”‚   â””â”€â”€ sw.js             # cache offline
â”œâ”€â”€ database/
â”‚   â””â”€â”€ postgres-schema.sql
â”œâ”€â”€ docs/
â”‚   â”œâ”€â”€ ARCHITECTURE.md
â”‚   â””â”€â”€ DEPLOYMENT.md
â”œâ”€â”€ screenshots/
â”œâ”€â”€ Dockerfile
â”œâ”€â”€ pom.xml
â””â”€â”€ env.example
```

> ObservaÃ§Ã£o tÃ©cnica: o backend atual preserva um servidor Java legado integrado ao Spring Boot para manter compatibilidade com o deploy existente. A prÃ³xima evoluÃ§Ã£o natural Ã© separar controllers, services, repositories, DTOs e models em pacotes Spring dedicados.

## Como Rodar Localmente

1. Clone o projeto.
2. Copie `env.example` para `.env` ou configure as variÃ¡veis no terminal.
3. Configure `DATABASE_URL` com a connection string do Neon/PostgreSQL.
4. Configure `MMCHECK_ADMIN_PASSWORD`.
5. Execute:

```bash
mvn clean package
java -jar target/mn-check-1.8.6.jar
```

Abra:

```text
http://localhost:4173
```

## VariÃ¡veis de Ambiente

```text
DATABASE_URL=postgresql://usuario:senha@host/neondb?sslmode=require
MMCHECK_ADMIN_PASSWORD=senha-inicial-do-admin
PORT=4173
```

## Deploy no Render

1. Crie um banco PostgreSQL no Neon.
2. Copie a connection string sem comandos `psql` ou `npx`.
3. No Render, configure:
   - `DATABASE_URL`
   - `MMCHECK_ADMIN_PASSWORD`
4. Use o Dockerfile do projeto.
5. FaÃ§a push para o GitHub.

Detalhes em [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Endpoints Principais

| MÃ©todo | Rota | Uso |
|---|---|---|
| `GET` | `/api/health` | status da aplicaÃ§Ã£o e banco |
| `GET` | `/api/version` | versÃ£o atual |
| `GET` | `/api/bootstrap` | dados iniciais da tela |
| `POST` | `/api/importar` | importar PDF de saldo |
| `POST` | `/api/saldos/produto` | adicionar produto manualmente ao saldo |
| `POST` | `/api/contagem` | salvar contagem fÃ­sica |
| `GET` | `/api/historico` | histÃ³rico operacional |
| `POST` | `/api/scanner/validate` | validar leitura CODE 128 |

## Testes

```bash
mvn test
```

Testes cobrem:

- leitura de PDF com pÃ¡ginas mÃºltiplas;
- SKU `76331.3.4`;
- linhas quebradas e colunas desalinhadas;
- soma de SKUs duplicados;
- validaÃ§Ã£o CODE 128;
- persistÃªncia PostgreSQL quando `DATABASE_URL` estÃ¡ disponÃ­vel.

## Diferenciais Para PortfÃ³lio

- Resolve um problema real de operaÃ§Ã£o logÃ­stica.
- Demonstra pensamento de produto e nÃ£o apenas CRUD.
- Usa persistÃªncia real com PostgreSQL/Neon.
- Manipula PDF com parser prÃ³prio e logs de auditoria.
- Possui UX operacional para celular/tablet.
- Inclui fluxo offline/sincronizaÃ§Ã£o.
- Implementa validaÃ§Ã£o de cÃ³digo de barras industrial.

## Melhorias Futuras

- Separar completamente o backend em controllers, services, repositories, DTOs e models.
- Criar pipeline CI/CD com screenshots automÃ¡ticos.
- Adicionar autenticaÃ§Ã£o JWT formal.
- Criar painel especÃ­fico de transportadoras.
- Adicionar testes end-to-end com Playwright.
- Integrar OCR opcional apenas como apoio, nunca como parser principal.

## Como Apresentar em Entrevistas

Explique o projeto como um sistema criado para reduzir erro operacional em expediÃ§Ã£o. Destaque:

- o problema real observado;
- a decisÃ£o de usar PostgreSQL para evitar perda de dados;
- o parser PDF com logs e tratamento de linhas ruins;
- a conferÃªncia pausÃ¡vel/retomÃ¡vel;
- a interface responsiva para operaÃ§Ã£o em tablet/celular;
- o botÃ£o de produto manual como fallback operacional pragmÃ¡tico.

## LicenÃ§a

MIT. Veja [`LICENSE`](LICENSE).

## Autor

**Marcos Neto**

- GitHub: adicione seu link aqui
- LinkedIn: adicione seu link aqui
- PortfÃ³lio: adicione seu link aqui
