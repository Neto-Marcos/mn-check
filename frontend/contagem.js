export function safeQuantity(value) {
  const number = Number.parseInt(value ?? 0, 10);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

export function signedQuantity(value) {
  const number = Number.parseInt(value ?? 0, 10);
  return Number.isFinite(number) ? number : 0;
}

export function normalizeCountRows(rows) {
  return (rows || []).map((item) => ({
    ...item,
    system: safeQuantity(item.system),
    counted: safeQuantity(item.counted),
    assistance: safeQuantity(item.assistance),
    damaged: safeQuantity(item.damaged),
    other: signedQuantity(item.other)
  }));
}

export function countAccounted(item) {
  return safeQuantity(item.counted) + safeQuantity(item.assistance) + safeQuantity(item.damaged) + signedQuantity(item.other);
}

export function countDifference(item) {
  return countAccounted(item) - safeQuantity(item.system);
}

export function hasCountMovement(item) {
  return safeQuantity(item.counted) > 0
    || safeQuantity(item.assistance) > 0
    || safeQuantity(item.damaged) > 0
    || signedQuantity(item.other) !== 0;
}
