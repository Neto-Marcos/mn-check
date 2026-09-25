export function isExplicitlyCounted(item) {
  return item?.estado === "CONTADO" || item?.ultimaOcorrenciaId != null;
}

export function initialCountQuantity(item) {
  return isExplicitlyCounted(item) ? (item?.quantidadeContada ?? 0) : 1;
}

export function countActionType(item) {
  return isExplicitlyCounted(item) ? "CORRECAO" : "DEFINIR";
}

export function countDisplay(item) {
  return isExplicitlyCounted(item) ? `${item?.quantidadeContada ?? 0} un` : "Pendente";
}

export function normalizePartialProducts(items) {
  const bySku = new Map();
  (Array.isArray(items) ? items : []).forEach((item) => {
    const sku = String(item?.sku || "").trim();
    if (!sku || bySku.has(sku)) return;
    bySku.set(sku, {
      sku,
      description: String(item?.description || item?.descricao || "").trim()
    });
  });
  return [...bySku.values()].sort((left, right) => left.sku.localeCompare(right.sku, "pt-BR", { numeric: true }));
}

export function filterPartialProducts(items, query) {
  const normalizedQuery = String(query || "").trim().toLocaleLowerCase("pt-BR");
  if (!normalizedQuery) return Array.isArray(items) ? items : [];
  return (Array.isArray(items) ? items : []).filter((item) =>
    String(item?.sku || "").toLocaleLowerCase("pt-BR").includes(normalizedQuery)
      || String(item?.description || "").toLocaleLowerCase("pt-BR").includes(normalizedQuery)
  );
}

export function partialSkuPayload(selectedSkus, products) {
  const allowed = new Set((Array.isArray(products) ? products : []).map((item) => String(item?.sku || "").trim()));
  return [...new Set(Array.isArray(selectedSkus) ? selectedSkus.map((sku) => String(sku || "").trim()) : [])]
    .filter((sku) => sku && allowed.has(sku));
}
