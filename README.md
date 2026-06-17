# MM Check — Sistema Inteligente de Conferência e Contagem

![Java](https://img.shields.io/badge/Java-21-ef4444?style=for-the-badge)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-16a34a?style=for-the-badge)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL%2FNeon-Persistence-2563eb?style=for-the-badge)
![PDFBox](https://img.shields.io/badge/PDFBox-PDF%20Parser-f97316?style=for-the-badge)
![Render](https://img.shields.io/badge/Render-Deploy-111827?style=for-the-badge)

> Projeto desenvolvido para solucionar problemas reais de expedição, conferência e contagem de estoque em ambiente logístico.

O **MM Check** é um sistema operacional para controle de mapas de carga, separação, reconferência de expedição e contagem de estoque. Ele foi criado a partir de um fluxo real de logística industrial, onde erros de SKU, cor, voltagem e divergência de saldo geram retrabalho, atraso e risco operacional.

## Preview

As capturas profissionais ficam em [`screenshots/`](screenshots/). Gere novas imagens após rodar o sistema localmente ou em produção.

| Dashboard | Conferência | Contagem |
|---|---|---|
| `screenshots/dashboard.png` | `screenshots/conferencia.png` | `screenshots/contagem.png` |

## Problema Resolvido

- Conferência manual sujeita a erro humano.
- Troca de SKU, cor ou voltagem na expedição.
- Contagem de estoque sem histórico confiável.
- Importação de saldo por PDF com linhas quebradas e colunas desalinhadas.
- Perda de dados após reinício do servidor.
- Falta de rastreabilidade sobre divergências e correções.

## Solução

O MM Check centraliza o fluxo operacional:

- separador registra itens separados;
- expedição reconfere por coletor/bipador;
- estoque importa saldo por PDF e registra contagens;
- administradores acompanham histórico, divergências, usuários e relatórios;
- dados críticos persistem em PostgreSQL/Neon.

## Funcionalidades

- Conferência inteligente por código de barras CODE 128 via coletor USB/Bluetooth.
- Validação de SKU, cor e voltagem.
- Pausar, retomar ou cancelar conferências com confirmação.
- Salvamento parcial da conferência.
- Importação de saldo por PDF usando Apache PDFBox.
- Parser com leitura de todas as páginas, duplicidade somada e debug detalhado.
- Botão **Adicionar produto** na contagem para corrigir item não lido pelo PDF.
- Histórico real em PostgreSQL.
- Versionamento de saldo e preservação de contagens anteriores.
- Dashboard operacional com indicadores.
- Modo escuro profissional.
- Interface responsiva para desktop, tablet e celular.
- Relatório A4 para imprimir/salvar contagem.
- Service worker para manter interface e operações recentes disponíveis no aparelho.

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
├── backend/
│   ├── src/              # servidor, parser PDF, scanner, PostgreSQL e regras de negócio
│   └── test/             # testes de parser, scanner e persistência
├── frontend/
│   ├── app.js            # interface React sem build externo
│   ├── styles.css        # design system responsivo
│   └── sw.js             # cache offline
├── database/
│   └── postgres-schema.sql
├── docs/
│   ├── ARCHITECTURE.md
│   └── DEPLOYMENT.md
├── screenshots/
├── Dockerfile
├── pom.xml
└── env.example
```

> Observação técnica: o backend atual preserva um servidor Java legado integrado ao Spring Boot para manter compatibilidade com o deploy existente. A próxima evolução natural é separar controllers, services, repositories, DTOs e models em pacotes Spring dedicados.

## Como Rodar Localmente

1. Clone o projeto.
2. Copie `env.example` para `.env` ou configure as variáveis no terminal.
3. Configure `DATABASE_URL` com a connection string do Neon/PostgreSQL.
4. Configure `MMCHECK_ADMIN_PASSWORD`.
5. Execute:

```bash
mvn clean package
java -jar target/mn-check-1.8.3.jar
```

Abra:

```text
http://localhost:4173
```

## Variáveis de Ambiente

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
5. Faça push para o GitHub.

Detalhes em [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Endpoints Principais

| Método | Rota | Uso |
|---|---|---|
| `GET` | `/api/health` | status da aplicação e banco |
| `GET` | `/api/version` | versão atual |
| `GET` | `/api/bootstrap` | dados iniciais da tela |
| `POST` | `/api/importar` | importar PDF de saldo |
| `POST` | `/api/saldos/produto` | adicionar produto manualmente ao saldo |
| `POST` | `/api/contagem` | salvar contagem física |
| `GET` | `/api/historico` | histórico operacional |
| `POST` | `/api/scanner/validate` | validar leitura CODE 128 |

## Testes

```bash
mvn test
```

Testes cobrem:

- leitura de PDF com páginas múltiplas;
- SKU `76331.3.4`;
- linhas quebradas e colunas desalinhadas;
- soma de SKUs duplicados;
- validação CODE 128;
- persistência PostgreSQL quando `DATABASE_URL` está disponível.

## Diferenciais Para Portfólio

- Resolve um problema real de operação logística.
- Demonstra pensamento de produto e não apenas CRUD.
- Usa persistência real com PostgreSQL/Neon.
- Manipula PDF com parser próprio e logs de auditoria.
- Possui UX operacional para celular/tablet.
- Inclui fluxo offline/sincronização.
- Implementa validação de código de barras industrial.

## Melhorias Futuras

- Separar completamente o backend em controllers, services, repositories, DTOs e models.
- Criar pipeline CI/CD com screenshots automáticos.
- Adicionar autenticação JWT formal.
- Criar painel específico de transportadoras.
- Adicionar testes end-to-end com Playwright.
- Integrar OCR opcional apenas como apoio, nunca como parser principal.

## Como Apresentar em Entrevistas

Explique o projeto como um sistema criado para reduzir erro operacional em expedição. Destaque:

- o problema real observado;
- a decisão de usar PostgreSQL para evitar perda de dados;
- o parser PDF com logs e tratamento de linhas ruins;
- a conferência pausável/retomável;
- a interface responsiva para operação em tablet/celular;
- o botão de produto manual como fallback operacional pragmático.

## Licença

MIT. Veja [`LICENSE`](LICENSE).

## Autor

**Marcos Neto**

- GitHub: adicione seu link aqui
- LinkedIn: adicione seu link aqui
- Portfólio: adicione seu link aqui
