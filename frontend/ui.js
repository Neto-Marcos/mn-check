export function initials(name) {
  return String(name || "MN")
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join("") || "MN";
}

export function status(value) {
  return {
    separacao: "separação",
    "aguardando conferencia": "aguardando conferência",
    conferencia: "conferência",
    perfeito: "conferido",
    conferido: "conferido",
    "corrigir problema": "corrigir problema",
  }[value] || value;
}

export function statusClass(value) {
  return {
    separacao: "status-open",
    "aguardando conferencia": "status-waiting",
    conferencia: "status-waiting",
    perfeito: "status-done",
    conferido: "status-done",
    "corrigir problema": "status-error",
  }[value] || "";
}

export function plural(value, singular, pluralText) {
  return `${value} ${value === 1 ? singular : pluralText}`;
}

export function formatDate(value) {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(value)).replace(",", "");
}
