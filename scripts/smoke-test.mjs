#!/usr/bin/env node

const BASE_URL = normalizeBaseUrl(process.env.MM_CHECK_BASE_URL || "http://127.0.0.1:4173");
const USERNAME = process.env.MM_CHECK_SMOKE_USER || "Marcos";
const PASSWORD = process.env.MM_CHECK_SMOKE_PASSWORD;

if (!PASSWORD) {
  fail("Defina MM_CHECK_SMOKE_PASSWORD antes de rodar o smoke test.");
}

const state = {
  token: "",
  user: null,
  importedSku: "76331.3.4",
  smokeMapId: `99${Date.now().toString().slice(-6)}`
};

const steps = [
  ["login", testLogin],
  ["dashboard principal", testDashboard],
  ["upload de PDF de saldo", testBalancePdfUpload],
  ["atualizacao de saldo", testBalanceRefresh],
  ["inicio de contagem", testCountingStart],
  ["salvar contagem", testSaveCount],
  ["conferencia por codigo", testBarcodeConference]
];

console.log(`MN Check smoke test -> ${BASE_URL}`);

for (const [name, test] of steps) {
  const started = Date.now();
  try {
    await test();
    console.log(`OK ${name} (${Date.now() - started}ms)`);
  } catch (error) {
    fail(`${name}: ${error.message}`);
  }
}

console.log("Smoke test concluido com sucesso.");

async function testLogin() {
  const body = await api("/api/login", {
    method: "POST",
    body: { username: USERNAME, password: PASSWORD },
    auth: false
  });
  assert(body.token, "login nao retornou token");
  assert(body.user?.username, "login nao retornou usuario");
  state.token = body.token;
  state.user = body.user;
}

async function testDashboard() {
  const body = await api("/api/bootstrap");
  assert(body.app === "MN - Check", "bootstrap nao retornou o app esperado");
  assert(body.version, "bootstrap nao retornou versao");
  assert(Array.isArray(body.maps), "bootstrap nao retornou mapas");
  assert(Array.isArray(body.counts), "bootstrap nao retornou contagem");
  assert(body.user?.username, "bootstrap nao retornou usuario logado");
}

async function testBalancePdfUpload() {
  const body = await api("/api/importar", {
    method: "POST",
    body: {
      fileName: `smoke-saldo-${Date.now()}.pdf`,
      contentType: "application/pdf",
      dataUrl: `data:application/pdf;base64,${buildBalancePdf().toString("base64")}`
    }
  });
  const row = findCount(body, state.importedSku);
  assert(row, `SKU ${state.importedSku} nao apareceu apos upload`);
  assert(Number(row.system) === 112, `saldo esperado 112 para ${state.importedSku}, recebido ${row.system}`);
  assert(body.countsUpdatedAt, "upload nao retornou data de atualizacao");
}

async function testBalanceRefresh() {
  const body = await api("/api/saldos");
  const row = findCount(body, state.importedSku);
  assert(row, `SKU ${state.importedSku} nao aparece em /api/saldos`);
  assert(Number(row.system) === 112, "saldo nao foi persistido apos importacao");
  assert(body.countsUpdatedAt, "/api/saldos nao retornou countsUpdatedAt");
}

async function testCountingStart() {
  const body = await api("/api/bootstrap");
  const row = findCount(body, state.importedSku);
  assert(row, "nao ha saldo importado para iniciar contagem");
  assert(["number", "string"].includes(typeof row.system), "item de contagem nao contem saldo do sistema");
}

async function testSaveCount() {
  const body = await api("/api/contagem", {
    method: "POST",
    body: {
      counts: [
        { sku: state.importedSku, system: 112, counted: 110, damaged: 1, other: 1 }
      ]
    }
  });
  const row = findCount(body, state.importedSku);
  assert(row, "contagem salva nao retornou o SKU testado");
  assert(Number(row.counted) === 110, "quantidade contada nao foi salva");
  assert(Number(row.damaged || 0) === 1, "quantidade de avaria nao foi salva");
  assert(Number(row.other || 0) === 1, "quantidade outros nao foi salva");
}

async function testBarcodeConference() {
  const draft = {
    id: state.smokeMapId,
    route: "SMOKE-ROTA",
    client: "SMOKE TESTE",
    carrier: "SMOKE",
    branch: "281",
    date: new Date().toISOString().slice(0, 10),
    status: "separacao",
    createdBy: state.user.id,
    orderNumbers: [`${state.smokeMapId}01`],
    attachmentName: "smoke.png",
    attachmentType: "image/png",
    attachmentPath: "",
    items: [
      {
        sku: "74266.1.3",
        name: "Produto smoke CODE 128",
        barcode: "7426613",
        quantity: 1,
        checkedQuantity: 0,
        ok: true
      }
    ]
  };

  await api("/api/maps/confirm", {
    method: "POST",
    body: {
      draft,
      files: [
        {
          fileName: "smoke.png",
          contentType: "image/png",
          dataUrl: onePixelPngDataUrl()
        }
      ]
    }
  });

  await api(`/api/maps/${encodeURIComponent(state.smokeMapId)}/send-conference`, {
    method: "POST",
    body: {}
  });

  const result = await api("/api/scanner/validate", {
    method: "POST",
    body: {
      mapId: state.smokeMapId,
      operator: state.user.name || state.user.username,
      scannedCode: "7426613",
      source: "scanner"
    }
  });

  assert(result.approved === true, `leitura deveria aprovar, recebido: ${JSON.stringify(result)}`);
  assert(result.status === "APROVADO", "status de conferencia por codigo nao aprovado");
}

async function api(path, options = {}) {
  const method = options.method || "GET";
  const headers = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.auth !== false) headers.Authorization = `Bearer ${state.token}`;

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });

  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText} ${body.error || text}`);
  }
  return body;
}

function findCount(body, sku) {
  return (body.counts || []).find((item) => item.sku === sku);
}

function buildBalancePdf() {
  const lines = [
    "Folha : 1",
    "Cod Filial Cod Produto Grade 'X' Grade 'Y' Produto Saldo Custo Medio Total",
    "281 76331 3 4 CAIXA DE SOM PHILIPS 112 100,00 11200,00",
    "281 73578 1 2 LAVADORA MIDEA 8 100,00 800,00"
  ];
  const content = [
    "BT",
    "/F1 9 Tf",
    "36 760 Td",
    ...lines.map((line, index) => `${index === 0 ? "" : "0 -18 Td "}${pdfText(line)} Tj`),
    "ET"
  ].join("\n");

  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${Buffer.byteLength(content)} >>\nstream\n${content}\nendstream`
  ];

  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  for (let index = 0; index < objects.length; index++) {
    offsets.push(Buffer.byteLength(pdf));
    pdf += `${index + 1} 0 obj\n${objects[index]}\nendobj\n`;
  }
  const xrefOffset = Buffer.byteLength(pdf);
  pdf += `xref\n0 ${objects.length + 1}\n`;
  pdf += "0000000000 65535 f \n";
  for (let index = 1; index < offsets.length; index++) {
    pdf += `${String(offsets[index]).padStart(10, "0")} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(pdf, "utf8");
}

function pdfText(value) {
  return `(${String(value).replaceAll("\\", "\\\\").replaceAll("(", "\\(").replaceAll(")", "\\)")})`;
}

function onePixelPngDataUrl() {
  return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
}

function normalizeBaseUrl(value) {
  return value.replace(/\/+$/, "");
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function fail(message) {
  console.error(`ERRO smoke test: ${message}`);
  process.exit(1);
}
