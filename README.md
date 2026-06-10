# MN - Check

Controle de separação, conferência e estoque.

Versão atual: **1.6.3**

## Leitor CODE 128

A tela de **Conferência** possui um leitor industrial mobile-first:

- câmera traseira iniciada automaticamente;
- leitura contínua de CODE 128 com `html5-qrcode`;
- botão para fotografar a etiqueta e processar a imagem no servidor;
- preferência pela API nativa `BarcodeDetector` quando disponível;
- linha verde para posicionamento da etiqueta;
- confirmação por som e vibração;
- cooldown de 1 segundo contra leituras duplicadas;
- entrada manual;
- scanners USB e Bluetooth que funcionam como teclado;
- validação no backend Spring Boot;
- histórico persistente no PostgreSQL.

Na leitura por fotografia, o backend testa rotações e diferentes recortes da imagem. Para melhores resultados, mantenha todas as barras visíveis, aproxime a câmera e evite reflexos.

Ao criar um mapa pela câmera, o operador informa manualmente:

- o número do mapa;
- todos os números de pedido existentes no mapa.

Os pedidos podem ser separados por vírgula, espaço ou quebra de linha. Esses identificadores têm prioridade sobre qualquer texto interpretado na fotografia, e o sistema impede o cadastro de um número de mapa já existente.

Na tela de contagem, a lista de saldo pode ser pesquisada por:

- SKU digitado;
- scanner USB ou Bluetooth seguido de Enter;
- fotografia da etiqueta CODE 128.

A comparação ignora pontos e hífens, destaca o SKU encontrado e posiciona o cursor no campo de quantidade contada.

Padrão do produto:

```text
SKU.COR.VOLTAGEM
```

Os valores `7426613`, `74266 1 3` e `74266.1.3` são normalizados para:

```text
74266.1.3
```

Regras:

- SKU: primeiros 5 números;
- cor: penúltimo número;
- voltagem: último número.

Tabela de voltagem industrial:

- `0` ou `4`: Bivolt;
- `1` ou `3`: 127V;
- `2`: 220V.

Os códigos `1` e `3` são considerados equivalentes entre si, assim como `0` e `4`.

O backend consulta o próximo item pendente do mapa autenticado. O navegador não decide livremente qual produto é esperado.

Exemplo:

```text
Esperado: 74266.1.3
Lido:     74266.1.2
Status:   BLOQUEADO
Motivo:   Voltagem incorreta
```

Leituras bloqueadas ficam no histórico, mas não incrementam a quantidade conferida.

### Celular

- Use o endereço HTTPS do Render.
- No Android, abra no Chrome e autorize a câmera.
- No iPhone, abra no Safari e autorize a câmera.
- Posicione o código de barras horizontalmente sobre a linha verde.

### Scanner USB ou Bluetooth

1. Conecte ou pareie o scanner.
2. Configure o scanner para enviar `Enter` após a leitura, comportamento padrão da maioria dos modelos.
3. Abra a conferência.
4. Leia a etiqueta. O campo reconhece automaticamente a digitação rápida como scanner físico.

### API do scanner

Validar leitura:

```http
POST /api/scanner/validate
Authorization: Bearer TOKEN
Content-Type: application/json

{
  "mapId": "15740",
  "scannedCode": "7426613",
  "operator": "Marcos",
  "source": "camera"
}
```

Resposta aprovada:

```json
{
  "status": "APROVADO",
  "approved": true,
  "expected": "74266.1.3",
  "scanned": "74266.1.3",
  "reason": "Produto correto"
}
```

Outras rotas:

| Método | Endpoint | Função |
|---|---|---|
| `POST` | `/api/scanner/validate` | Validar e registrar uma leitura |
| `GET` | `/api/scanner/history?mapId=15740` | Histórico persistente do mapa |
| `POST` | `/api/scanner/decode` | Decodificar imagem CODE 128 com ZXing Java |

`POST /api/scanner/decode` usa `multipart/form-data` com o campo `file`.

## Arquitetura

O processo principal é Spring Boot. O módulo novo de scanner é nativo em Spring MVC. Durante a migração, as rotas operacionais anteriores são encaminhadas internamente ao núcleo legado na porta `4174`, preservando login, mapas, estoque e usuários.

Bibliotecas principais:

- Spring Boot 3.5;
- ZXing 3.5 para decodificação Java;
- html5-qrcode 2.3.8 no navegador;
- PostgreSQL JDBC;
- Apache PDFBox.

## Persistência

O backend exige PostgreSQL e não utiliza banco em memória, SQLite ou arquivos JSON/TXT locais.

Os dados de saldo e contagem usam as tabelas relacionais:

- `importacoes_saldo`;
- `saldos`;
- `contagens`;
- `itens_contagem`.
- `historico_scanner`.

Usuários, mapas, notificações e anexos também permanecem no PostgreSQL. As tabelas são criadas automaticamente com `CREATE TABLE IF NOT EXISTS`.

## Criar o banco no Neon

1. Acesse [Neon](https://neon.tech/) e crie uma conta.
2. Clique em **New project**.
3. Escolha um nome, por exemplo `mn-check`.
4. Mantenha PostgreSQL e uma região próxima do Render.
5. Abra **Dashboard > Connection Details**.
6. Selecione a conexão `Pooled connection`.
7. Copie a URL completa.

Formato esperado:

```text
postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require
```

Não publique essa URL no GitHub. Ela contém a senha do banco.

## Configurar no Render

1. Abra o serviço `mm-check` no Render.
2. Entre em **Environment**.
3. Crie ou substitua `DATABASE_URL` pela URL copiada do Neon.
4. Mantenha `MMCHECK_ADMIN_PASSWORD` configurada.
5. Salve as variáveis.
6. Execute **Manual Deploy > Deploy latest commit**.
7. Confira `/api/health`; o campo `database` deve indicar PostgreSQL.

O arquivo `render.yaml` declara `DATABASE_URL` como variável secreta (`sync: false`).

## Rodar localmente

Requisitos:

- Java 21;
- Maven 3.9+;
- um banco PostgreSQL Neon acessível.

PowerShell:

```powershell
$env:DATABASE_URL="postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require"
$env:MMCHECK_ADMIN_PASSWORD="senha-inicial-segura"
mvn test
mvn package
java -jar target/mn-check-1.6.3.jar
```

Abra:

```text
http://127.0.0.1:4173/
```

Sem `DATABASE_URL`, o servidor interrompe a inicialização. Isso evita executar acidentalmente com dados descartáveis.

## Docker

```bash
docker build -t mn-check .
docker run --rm -p 4173:4173 \
  -e DATABASE_URL="postgresql://usuario:senha@ep-xxxxx.us-east-2.aws.neon.tech/neondb?sslmode=require" \
  -e MMCHECK_ADMIN_PASSWORD="senha-inicial-segura" \
  mn-check
```

## Importação de saldo

1. Entre como administrador ou conferente de estoque.
2. Abra **Contagem**.
3. Selecione o PDF de saldo.
4. Aguarde a leitura e confira os indicadores.

O Apache PDFBox percorre todas as páginas. O parser identifica Produto, Grade X, Grade Y e Saldo e monta:

```text
produto-gradeX.gradeY
```

Exemplo:

```text
74683-1.2
```

Linhas vazias, cabeçalhos, totais, rodapés e `9999999` são ignorados. Duplicidades são contabilizadas e saldos conflitantes bloqueiam a importação. A IA não participa da leitura principal.

Após a validação, a importação e seus saldos são gravados em uma única transação PostgreSQL.

Log esperado:

```text
SALDO_PDF arquivo="saldo.pdf" paginas=5 skus=242 linhas_ignoradas=27 duplicados=0 conflitos=0 duracao_ms=259
SALDO_POSTGRES importacao_id=1 arquivo="saldo.pdf" skus=242 atualizado_em=2026-06-10T...
```

## Histórico

`GET /api/historico` consulta importações e contagens diretamente no PostgreSQL. Os registros continuam disponíveis após reinício ou novo deploy do serviço.

## Impressão A4

1. Abra **Contagem**.
2. Preencha as quantidades.
3. Clique em **Atualizar contagem**.
4. Clique em **Imprimir contagem**.

O relatório mantém PDF de origem, datas, SKU, saldo, contado, diferença, status, totais e assinaturas. O navegador permite imprimir ou salvar como PDF.

## Endpoints

| Método | Endpoint | Função |
|---|---|---|
| `GET` | `/api/health` | Saúde, versão e banco |
| `GET` | `/api/version` | Versão pública |
| `POST` | `/api/login` | Autenticação |
| `GET` | `/api/bootstrap` | Dados permitidos ao usuário |
| `GET` | `/api/saldos` | Última importação e saldo real |
| `POST` | `/api/importar` | Importar PDF e gravar no PostgreSQL |
| `POST` | `/api/contagem` | Gravar contagem e itens |
| `GET` | `/api/historico` | Histórico persistente |
| `POST` | `/api/scanner/validate` | Validar CODE 128 |
| `GET` | `/api/scanner/history` | Histórico de leituras |
| `POST` | `/api/scanner/decode` | Ler CODE 128 de uma imagem |

## Testes

O GitHub Actions inicia PostgreSQL 17 e executa:

```bash
mvn test
mvn package
node --check frontend/app.js
docker build -t mn-check-ci .
```

`PostgresDatabaseTest` comprova:

- conexão JDBC;
- criação automática das tabelas;
- gravação da importação;
- gravação da contagem;
- leitura do histórico;
- persistência ao criar uma nova conexão, simulando reinício.
- parser do padrão SKU.COR.VOLTAGEM;
- bloqueio específico por SKU, cor ou voltagem;
- decodificação de imagem CODE 128 com ZXing.

Para uma validação no Neon, execute `mvn test` com a `DATABASE_URL` real configurada.
