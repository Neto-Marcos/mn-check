export function safeQuantity(value) {
  const number = Number.parseInt(value ?? 0, 10);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

export function normalizeCountRows(rows) {
  return (rows || []).map((item) => ({
    ...item,
    system: safeQuantity(item.system),
    counted: safeQuantity(item.counted),
    damaged: safeQuantity(item.damaged),
    other: safeQuantity(item.other)
  }));
}

export function countAccounted(item) {
  return safeQuantity(item.counted) + safeQuantity(item.damaged) + safeQuantity(item.other);
}

export function countDifference(item) {
  return countAccounted(item) - safeQuantity(item.system);
}

export function hasCountMovement(item) {
  return countAccounted(item) > 0;
}
