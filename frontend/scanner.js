export function voltageFromSku(sku) {
  const gradeY = String(sku || "").split(".").pop();
  return {
    "0": "Bivolt",
    "1": "127V",
    "2": "220V",
    "3": "127V",
    "4": "Bivolt",
  }[gradeY] || "Não informado";
}

export function normalizeProductCode(value) {
  const digits = String(value || "").replace(/\D/g, "");
  if (digits.length !== 7) return value || "---";
  return `${digits.slice(0, 5)}.${digits[5]}.${digits[6]}`;
}

export function normalizeInventorySku(value) {
  const cleaned = String(value || "").trim().replace(/[,/-]/g, ".");
  if (/^\d{4,8}\.\d{1,3}\.\d{1,3}$/.test(cleaned)) return cleaned;
  const digits = cleaned.replace(/\D/g, "");
  if (digits.length >= 7 && digits.length <= 10) {
    return `${digits.slice(0, -2)}.${digits.slice(-2, -1)}.${digits.slice(-1)}`;
  }
  return cleaned;
}

export function validateBarcodeLocally(expectedValue, scannedValue) {
  const expected = String(expectedValue || "").replace(/\D/g, "");
  const scanned = String(scannedValue || "").replace(/\D/g, "");
  if (expected.length !== 7 || scanned.length !== 7) {
    return {
      approved: false,
      status: "BLOQUEADO",
      reason: "O código deve conter SKU de 5 dígitos, cor e voltagem.",
      expected: normalizeProductCode(expectedValue),
      scanned: normalizeProductCode(scannedValue)
    };
  }
  let reason = "Produto correto";
  if (expected.slice(0, 5) !== scanned.slice(0, 5)) reason = "SKU incorreto";
  else if (expected[5] !== scanned[5]) reason = "Cor incorreta";
  else if (voltageGroup(expected[6]) !== voltageGroup(scanned[6])) reason = "Voltagem incorreta";
  const approved = reason === "Produto correto";
  return {
    approved,
    status: approved ? "APROVADO" : "BLOQUEADO",
    reason,
    expected: normalizeProductCode(expected),
    scanned: normalizeProductCode(scanned)
  };
}

export function scanSourceLabel(source) {
  return {
    scanner: "coletor/bipador",
    manual: "digitação manual"
  }[source] || "leitura";
}

export function playFeedback(success) {
  if (navigator.vibrate) navigator.vibrate(success ? 90 : [80, 50, 80]);
  try {
    const context = new (window.AudioContext || window.webkitAudioContext)();
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.value = success ? 880 : 220;
    gain.gain.value = 0.05;
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    oscillator.stop(context.currentTime + (success ? 0.12 : 0.28));
  } catch (_) {}
}

function voltageGroup(code) {
  if (code === "0" || code === "4") return "bivolt";
  if (code === "1" || code === "3") return "127";
  if (code === "2") return "220";
  return "invalid";
}
