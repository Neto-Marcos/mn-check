#!/usr/bin/env node

import http from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";

const root = join(process.cwd(), "frontend");
const port = Number(process.env.PORT || 4174);
const branchId = "00000000-0000-0000-0000-000000000001";
const productId = "10000000-0000-0000-0000-000000000001";
const receiptId = "20000000-0000-0000-0000-000000000001";
const mapId = "30000000-0000-0000-0000-000000000001";

const user = {
  id: "preview-admin", username: "Marcos", name: "Marcos", role: "admin", label: "Administrador",
  allowedViews: ["admin", "overview", "separation", "conference", "routes", "counting", "history", "users"]
};

const workspace = {
  branchId,
  branches: [{ id: branchId, codigo: "MATRIZ", nome: "Matriz", cnpj: "12.345.678/0001-90", ativa: true }],
  products: [{ id: productId, sku: "1191.3.1", codigo_interno: "119131", descricao: "Lavadora automática 13 kg", eans: "7891234567890", ativo: true }],
  balances: [
    { produto_id: productId, filial_codigo: "MATRIZ", sku: "1191.3.1", codigo_interno: "119131", descricao: "Lavadora automática 13 kg", fisico: 257, disponivel: 184, reservado: 68, em_transito: 12, quarentena: 5 },
    { produto_id: "10000000-0000-0000-0000-000000000002", filial_codigo: "MATRIZ", sku: "12553.3.1", codigo_interno: "1255331", descricao: "Refrigerador duplex 400 L", fisico: 96, disponivel: 74, reservado: 22, em_transito: 0, quarentena: 0 }
  ],
  receipts: [{ id: receiptId, filial_id: branchId, referencia: "CARGA-0818-A", fornecedor: "Indústria Nacional S.A.", status: "EM_RECEBIMENTO", documentos: 3, esperado: 280, recebido: 214, quarentena: 2, criado_em: new Date().toISOString() }],
  maps: [{ id: mapId, filial_id: branchId, numero_mapa: "MAPA-28451", pedidos: "84021, 84022", cliente: "Operação Nordeste", rota: "Rota 07", status: "PUBLICADO", esperado: 90, separado: 68, conferido: 0, criado_em: new Date().toISOString() }],
  transfers: [{ id: "40000000-0000-0000-0000-000000000001", referencia: "TRF-2026-014", filial_origem: "Matriz", filial_destino: "Filial Recife", status: "EM_TRANSITO", quantidade: 12, quantidade_recebida: 0, criado_em: new Date().toISOString() }],
  exceptions: [{ id: "50000000-0000-0000-0000-000000000001", filial: "Matriz", filial_id: branchId, operacao_tipo: "RECEBIMENTO", operacao_id: receiptId, tipo: "EXCESSO", quantidade: 2, descricao: "Quantidade recebida acima da NF-e.", status: "ABERTA", criado_por: "Ana Souza", criado_em: new Date().toISOString() }],
  movements: [
    { id: "m1", filial: "Matriz", sku: "1191.3.1", codigo_interno: "119131", descricao: "Lavadora automática 13 kg", tipo: "RESERVA", delta_fisico: 0, delta_disponivel: -10, delta_reservado: 10, origem_tipo: "MAPA", origem_id: "MAPA-28451", criado_por: "João Lima", ocorrido_em: new Date().toISOString() },
    { id: "m2", filial: "Matriz", sku: "12553.3.1", codigo_interno: "1255331", descricao: "Refrigerador duplex 400 L", tipo: "RECEBIMENTO", delta_fisico: 22, delta_disponivel: 22, delta_reservado: 0, origem_tipo: "RECEBIMENTO", origem_id: "CARGA-0818-A", criado_por: "Ana Souza", ocorrido_em: new Date(Date.now() - 3600000).toISOString() }
  ],
  audits: [
    { id: "a1", filial: "Matriz", usuario_id: "admin", acao: "MAPA_PUBLICADO", entidade_tipo: "MAPA", entidade_id: "MAPA-28451", detalhes: "{}", criado_em: new Date().toISOString() },
    { id: "a2", filial: "Matriz", usuario_id: "ana", acao: "RECEBIMENTO_INICIADO", entidade_tipo: "RECEBIMENTO", entidade_id: "CARGA-0818-A", detalhes: "{}", criado_em: new Date(Date.now() - 3600000).toISOString() }
  ],
  printers: [{ id: "60000000-0000-0000-0000-000000000001", filial_id: branchId,
    filial_nome: "Matriz", nome: "Zebra Recebimento 01", fabricante: "ZEBRA",
    largura_mm: 60, altura_mm: 40, dpi: 203, ativa: true }],
  parameters: [],
  profile: {}, profiles: [],
  dashboard: { stock: { fisico: 353, disponivel: 258, reservado: 90, em_transito: 12, quarentena: 5, skus: 257 }, pendingReceipts: 4, pendingMaps: 7, openExceptions: 3 }
};

const receipt = {
  id: receiptId, filial_id: branchId, referencia: "CARGA-0818-A", fornecedor: "Indústria Nacional S.A.", status: "EM_RECEBIMENTO",
  documents: [{ id: "nfe1", chave_nfe: "35260812345678000199550010000001231123456789", emitente: "Indústria Nacional S.A." }],
  items: [{ id: "ri1", produto_id: productId, produto_codigo: "119131", produto_descricao: "Lavadora automática 13 kg", quantidade_esperada: 80, quantidade_recebida: 62, quantidade_avariada: 1, quantidade_quarentena: 2, status: "DIVERGENTE" }],
  exceptions: workspace.exceptions
};

const map = {
  id: mapId, filial_id: branchId, numero_mapa: "MAPA-28451", cliente: "Operação Nordeste", rota: "Rota 07", status: "PUBLICADO",
  items: [{ id: "mi1", produto_id: productId, codigo_interno: "119131", sku: "1191.3.1", descricao: "Lavadora automática 13 kg", quantidade_esperada: 90, quantidade_separada: 68, quantidade_conferida: 0, quantidade_cancelada: 0, status: "PARCIAL" }],
  exceptions: []
};

const transfer = {
  id: "40000000-0000-0000-0000-000000000001", referencia: "TRF-2026-014",
  filial_origem_id: branchId, filial_destino_id: "00000000-0000-0000-0000-000000000002",
  filial_origem: "Matriz", filial_destino: "Filial Recife", status: "EM_TRANSITO",
  items: [{ id: "ti1", produto_id: productId, codigo_interno: "119131", sku: "1191.3.1",
    eans: "7891234567890", descricao: "Lavadora automática 13 kg", quantidade: 12,
    quantidade_recebida: 0, quantidade_quarentena: 0 }], exceptions: []
};

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host}`);
    if (url.pathname === "/api/version") return json(response, { app: "MN - Check", version: "3.0.0", buildAt: "preview", commit: "local" });
    if (url.pathname === "/api/login" && request.method === "POST") return json(response, { token: "preview-token", user });
    if (url.pathname === "/api/bootstrap") return json(response, { user, version: "3.0.0", maps: [], routes: [], users: [user], counts: [], notifications: [], metrics: {} });
    if (url.pathname === "/api/notifications") return json(response, { notifications: [] });
    if (url.pathname === "/api/v2/workspace") return json(response, workspace);
    if (url.pathname === "/api/v2/search") return json(response, { results: [
      { tipo: "PRODUTO", id: productId, titulo: "119131", subtitulo: "1191.3.1 · Lavadora automática 13 kg" },
      { tipo: "MAPA", id: mapId, titulo: "MAPA-28451", subtitulo: "Operação Nordeste · PUBLICADO" }
    ] });
    if (url.pathname === `/api/v2/receipts/${receiptId}`) return json(response, receipt);
    if (url.pathname === `/api/v2/maps/${mapId}`) return json(response, map);
    if (url.pathname === `/api/v2/transfers/${transfer.id}`) return json(response, transfer);
    if (url.pathname.startsWith("/api/v2/") && request.method !== "GET") return json(response, { status: "PREVIEW" });
    if (url.pathname.startsWith("/api/")) return json(response, { error: "Rota não simulada na prévia." }, 404);
    return serveFile(url.pathname, response);
  } catch (error) {
    return json(response, { error: error.message }, 500);
  }
});

server.listen(port, "127.0.0.1", () => console.log(`MN Check preview em http://127.0.0.1:${port}`));

async function serveFile(pathname, response) {
  const relative = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const path = normalize(join(root, relative));
  if (!path.startsWith(root)) return json(response, { error: "Caminho inválido." }, 400);
  try {
    if (!(await stat(path)).isFile()) throw new Error("not file");
    const content = await readFile(path);
    response.writeHead(200, { "Content-Type": mime(extname(path)), "Cache-Control": "no-store" });
    response.end(content);
  } catch (_) {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Não encontrado");
  }
}

function json(response, body, status = 200) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  response.end(JSON.stringify(body));
}

function mime(extension) {
  return ({ ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".png": "image/png", ".webmanifest": "application/manifest+json" })[extension] || "application/octet-stream";
}
