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
