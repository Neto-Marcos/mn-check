# MN Check 3.0 - Plataforma logística empresarial

![Java](https://img.shields.io/badge/Java-21-ef4444?style=for-the-badge)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-16a34a?style=for-the-badge)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL%2FNeon-Persistence-2563eb?style=for-the-badge)
![PDFBox](https://img.shields.io/badge/PDFBox-PDF%20Parser-f97316?style=for-the-badge)
![Railway](https://img.shields.io/badge/Railway-Deploy-111827?style=for-the-badge)

O **MN Check** controla o fluxo completo da mercadoria: NF-e, descarga, etiqueta interna, estoque, reserva, separação, reconferência, expedição e transferência entre filiais. A versão 3.0 mantém a contagem 2.3.0 aprovada e adiciona um livro imutável de movimentos ao PostgreSQL.

A produção anterior está preservada na tag `v2.3.0-recovered`; as evidências e o hash do artefato estão em [`docs/RECOVERY-2.3.0.md`](docs/RECOVERY-2.3.0.md).

## Fluxo empresarial 3.0

1. Uma carga agrupa uma ou mais NF-e importadas por XML.
2. A descarga é confirmada por EAN, SKU ou código interno.
3. Itens confirmados entram no estoque; exceções ficam em quarentena.
4. O PDF do mapa é extraído, revisado por uma pessoa e publicado.
5. A publicação reduz o disponível e aumenta o reservado, sem baixar o físico.
6. Separação e reconferência usam leituras independentes.
7. A expedição reduz o físico e consome a reserva.
8. Transferências controlam origem, trânsito e recebimento no destino.
9. Ajustes e estornos geram novos movimentos; registros anteriores não são apagados.

## Principais recursos

- Controle multi-filial e perfis por função.
- Cadastro de produtos, código interno e múltiplos EANs.
- Livro de estoque com físico, disponível, reservado, em trânsito e quarentena.
- Importação segura e determinística de XML de NF-e.
- Etiquetas térmicas Zebra/Argox com registro de reimpressão.
- Impressoras e parâmetros operacionais configuráveis por filial.
- Fila offline com idempotência para evitar leituras duplicadas.
- Registro de avaria, autorização de faltas e quarentena supervisionada.
- Aprovação de divergências e ajustes por supervisor.
- Reconciliação entre o livro de movimentos e o saldo projetado.
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
│   ├── postgres-schema.sql   # schema legado/contagem
│   └── enterprise-schema.sql # schema empresarial normalizado
├── docs/
│   ├── ARCHITECTURE.md
│   └── DEPLOYMENT.md
├── frontend/
│   ├── app.js               # interface React sem build externo
│   ├── mapas.js             # leitura local de arquivos de mapa
│   ├── scanner.js           # normalizacao e validacao local de codigos
│   ├── state.js             # estado, constantes e configuracoes
│   ├── styles.css           # interface legada e contagem preservada
│   ├── enterprise.css       # design system empresarial isolado
│   ├── enterprise.js        # módulos empresariais
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
java -jar target/mn-check.jar
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
6. Ao gerar domínio no Railway, mantenha a porta definida pela variável `PORT`.

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
| `GET` | `/api/v2/workspace` | painel e contexto multi-filial |
| `POST` | `/api/v2/receipts` | abrir carga de recebimento |
| `POST` | `/api/v2/receipts/{id}/nfe` | importar XML de NF-e |
| `POST` | `/api/v2/receipts/{id}/damage` | registrar item avariado |
| `POST` | `/api/v2/receipts/{id}/finalize` | publicar entradas confirmadas |
| `POST` | `/api/v2/maps/{id}/publish` | reservar saldo para separação |
| `POST` | `/api/v2/maps/{id}/authorize-shortage` | autorizar falta e liberar reserva |
| `POST` | `/api/v2/maps/{id}/dispatch` | expedir e baixar saldo físico |
| `POST` | `/api/v2/transfers/{id}/receive` | receber transferência no destino |
| `PATCH` | `/api/v2/parameters/{chave}` | configurar operação por filial |
| `GET` | `/api/v2/inventory/reconcile` | reconciliar livro e projeções |

## Testes

```bash
mvn test
```

No Windows, se o Maven estiver instalado no perfil do usuario:

```powershell
.\scripts\test-local.ps1
```

Os testes cobrem parser de PDF/XML, normalização de SKU, código de barras e um fluxo empresarial completo em PostgreSQL real e descartável. Esse fluxo inclui concorrência de reservas, recebimento, avaria, quarentena, separação, expedição, transferências, contagem, estorno, idempotência, impressão, parâmetros, isolamento de filial e reconciliação.

## Autor

**Marcos Neto**

- GitHub: [Neto-Marcos](https://github.com/Neto-Marcos)

## Licenca

MIT. Veja [`LICENSE`](LICENSE).
