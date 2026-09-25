import assert from "node:assert/strict";
import test from "node:test";
import {
  countActionType,
  countDisplay,
  initialCountQuantity,
  isExplicitlyCounted
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
