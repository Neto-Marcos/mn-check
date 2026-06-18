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
  if (navigator.vibrate) navigator.vibrate(success ? 110 : [120, 70, 120, 70, 160]);
  try {
    const context = new (window.AudioContext || window.webkitAudioContext)();
    const resume = context.resume?.();
    resume?.catch?.(() => {});
    const now = context.currentTime;
    const tones = success
      ? [
          { start: 0, frequency: 1046, duration: 0.1 },
          { start: 0.13, frequency: 1318, duration: 0.12 },
        ]
      : [
          { start: 0, frequency: 260, duration: 0.16 },
          { start: 0.18, frequency: 180, duration: 0.18 },
          { start: 0.38, frequency: 120, duration: 0.24 },
        ];

    tones.forEach((tone) => {
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      const startAt = now + tone.start;
      const endAt = startAt + tone.duration;
      oscillator.type = success ? "sine" : "square";
      oscillator.frequency.setValueAtTime(tone.frequency, startAt);
      gain.gain.setValueAtTime(0.0001, startAt);
      gain.gain.exponentialRampToValueAtTime(success ? 0.22 : 0.28, startAt + 0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001, endAt);
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.start(startAt);
      oscillator.stop(endAt + 0.02);
    });
    window.setTimeout(() => context.close?.(), success ? 360 : 820);
  } catch (_) {}
}

function voltageGroup(code) {
  if (code === "0" || code === "4") return "bivolt";
  if (code === "1" || code === "3") return "127";
  if (code === "2") return "220";
  return "invalid";
}
