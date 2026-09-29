import assert from "node:assert/strict";
import test from "node:test";
import {
  countActionType,
  countDisplay,
  filterPartialProducts,
  initialCountQuantity,
  isExplicitlyCounted,
  normalizePartialProducts,
  partialSkuPayload
} from "./inventory_counting_logic.js";

test("zero explícito permanece contado e vira correção", () => {
  const item = { estado: "CONTADO", quantidadeContada: 0, ultimaOcorrenciaId: 41 };
  assert.equal(isExplicitlyCounted(item), true);
  assert.equal(initialCountQuantity(item), 0);
  assert.equal(countActionType(item), "CORRECAO");
  assert.equal(countDisplay(item), "0 un");
});

test("item pendente inicia em um sem fingir contagem existente", () => {
  const item = { estado: "PENDENTE", quantidadeContada: 0, ultimaOcorrenciaId: null };
  assert.equal(isExplicitlyCounted(item), false);
  assert.equal(initialCountQuantity(item), 1);
  assert.equal(countActionType(item), "DEFINIR");
  assert.equal(countDisplay(item), "Pendente");
});

test("produtos do seletor parcial são normalizados e deduplicados sem saldo", () => {
  const products = normalizePartialProducts([
    { sku: " 72982.1.4 ", description: "Fogão Suggar", system: 99 },
    { sku: "1363.1.1", descricao: "Panela Britânia", saldo: 7 },
    { sku: "72982.1.4", description: "Duplicado" },
    { sku: "", description: "Inválido" }
  ]);

  assert.deepEqual(products, [
    { sku: "1363.1.1", description: "Panela Britânia" },
    { sku: "72982.1.4", description: "Fogão Suggar" }
  ]);
  assert.equal(Object.hasOwn(products[0], "system"), false);
  assert.equal(Object.hasOwn(products[0], "saldo"), false);
});

test("busca parcial encontra SKU e descrição sem diferenciar maiúsculas", () => {
  const products = [
    { sku: "67102.1205.2", description: "Fritadeira Mondial" },
    { sku: "73241.3.4", description: "TV Philips" }
  ];

  assert.deepEqual(filterPartialProducts(products, "1205"), [products[0]]);
  assert.deepEqual(filterPartialProducts(products, "philips"), [products[1]]);
  assert.deepEqual(filterPartialProducts(products, ""), products);
});

test("payload parcial contém somente SKUs permitidos, únicos e selecionados", () => {
  const products = Array.from({ length: 30 }, (_, index) => ({ sku: `SKU-${index + 1}`, description: `Produto ${index + 1}` }));
  const selected = [...products.map((item) => item.sku), "SKU-1", "SKU-FORA"];
  const payload = partialSkuPayload(selected, products);

  assert.equal(payload.length, 30);
  assert.equal(new Set(payload).size, 30);
  assert.equal(payload.includes("SKU-FORA"), false);
});

// Exercise the actual audit component with sanitized API responses, including its action handler.
let auditHookValues = [];
globalThis.React = {
  createElement: (type, props, ...children) => ({ type, props, children: children.flat(Infinity) }),
  useState: () => [auditHookValues.shift(), () => {}],
  useEffect: () => {}, useRef: () => ({}), useMemo: (fn) => fn()
};
globalThis.window = {};
await import("./inventarios.js");

function renderAudit(items, mode = "CEGO", tab = "divergentes", round = 2, request = async () => ({ id: "task" })) {
  const audit = {
    inventoryModo: mode, inventoryStatus: "EM_INVESTIGACAO", rodadaNumero: round,
    rodadaTipo: round === 2 ? "RECONTAGEM" : "CONTAGEM", resumo: { totalItens: items.length, naoContados: 0 }, itens: items
  };
  auditHookValues = [audit, false, "", tab, new Set(), false];
  return window.MNCheckInventarios.ApuracaoScreen({
    inventory: { id: 42, modo: mode, nome: "TEST" }, branchCode: "281", request, onOpenInvestigations: () => {}
  });
}

function nodes(tree) {
  return tree && typeof tree === "object" ? [tree, ...(tree.children || []).flatMap(nodes)] : [];
}

const countedTask = { apuracaoId: 91, inventarioItemId: 17, sku: "SKU-ZERO", contado: true,
  quantidadeFisica: 0, estado: "CONTADO", saldoSnapshot: null, diferenca: null, podeInvestigar: true };

test("CEGO R2 mostra tarefa do backend com estado CONTADO e abre pelo ID da apuração", async () => {
  const requests = [];
  const tree = renderAudit([countedTask, { ...countedTask, sku: "SKU-SEM-TAREFA", podeInvestigar: false }],
    "CEGO", "divergentes", 2, async (url, options) => { requests.push({ url, options }); return { id: "task" }; });
  const all = nodes(tree);
  const actions = all.filter(n => n.type === "button" && n.children.includes("🔎 Investigar"));
  assert.equal(actions.length, 1);
  assert.ok(all.some(n => n.children.includes("Investigações disponíveis")));
  assert.ok(all.some(n => n.children.includes("SKU-ZERO")));
  assert.equal(all.some(n => n.children.includes("SKU-SEM-TAREFA")), false);
  await actions[0].props.onClick();
  assert.equal(requests[0].url, "/api/inventarios/42/investigacoes?branchCode=281");
  assert.equal(requests[0].options.body.apuracaoId, 91);
  assert.deepEqual(Object.keys(requests[0].options.body).sort(), ["apuracaoId", "causaSuspeita", "justificativa"]);
});

test("ação Investigar depende somente da capability, inclusive em NORMAL", () => {
  for (const mode of ["CEGO", "NORMAL"]) {
    const tree = renderAudit([{ ...countedTask, estado: "DIVERGENCIA_CONFIRMADA", podeInvestigar: false }], mode, "todos");
    assert.equal(nodes(tree).some(n => n.type === "button" && n.children.includes("🔎 Investigar")), false);
  }
});

test("CEGO R1 oferece recontagem server-side sem tentar filtrar estado protegido", () => {
  const tree = renderAudit([{ ...countedTask, podeInvestigar: false }], "CEGO", "divergentes", 1);
  assert.ok(nodes(tree).some(n => n.children.includes("Itens da rodada")));
  const recount = nodes(tree).find(n => n.children.includes("🚀 Iniciar Recontagem Cega (R2)"));
  assert.equal(recount.props.disabled, false);
});
