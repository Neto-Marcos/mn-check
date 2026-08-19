export const APP_VERSION = "3.0.0";
export const OFFLINE_SCAN_QUEUE = "mnCheckOfflineScans";
export const OFFLINE_BOOTSTRAP = "mnCheckOfflineBootstrap";
export const OFFLINE_COUNT_DRAFT = "mnCheckOfflineCountDraft";

export const MAP_FILE_TYPES = new Set([
  "application/pdf",
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/heic",
  "image/heif",
]);

export const MAP_FILE_ACCEPT = "application/pdf,.pdf,image/png,.png,image/jpeg,.jpg,.jpeg,image/webp,.webp,image/heic,.heic,image/heif,.heif";

export const ROLE_OPTIONS = [
  ["receiving", "Operador de recebimento"],
  ["separation", "Conferente de separação"],
  ["expedition", "Conferente de expedição"],
  ["stock", "Conferente de estoque"],
  ["supervisor", "Supervisor de filial"],
  ["auditor", "Auditor"],
  ["admin", "Administrador"],
];

export const TITLES = {
  admin: ["controle", "Admin Marcos"],
  overview: ["painel", "Visão geral"],
  separation: ["operação", "Separação"],
  counting: ["estoque", "Contagem"],
  conference: ["validação", "Conferência"],
  routes: ["logística", "Rotas"],
  history: ["admin", "Histórico"],
  users: ["admin", "Usuários"],
  settings: ["conta", "Configurações"],
  receiving: ["entrada", "Recebimentos"],
  inventory: ["controle", "Estoque"],
  transfers: ["logística", "Transferências"],
  exceptions: ["aprovação", "Divergências"],
  audit: ["rastreabilidade", "Auditoria"],
  catalog: ["estrutura", "Cadastros"],
};

export const BOTTOM_NAV_PRIORITY = ["overview", "receiving", "separation", "conference", "inventory", "transfers", "counting", "settings"];

export function readStoredJson(key, fallback) {
  try {
    const value = JSON.parse(localStorage.getItem(key) || "null");
    return value ?? fallback;
  } catch (_) {
    return fallback;
  }
}

export function readOfflineScanQueue() {
  const queue = readStoredJson(OFFLINE_SCAN_QUEUE, []);
  return Array.isArray(queue) ? queue : [];
}

export function saveOfflineCountDraft(counts, user) {
  localStorage.setItem(OFFLINE_COUNT_DRAFT, JSON.stringify({
    counts,
    operator: user?.name || user?.username || "Operador",
    savedAt: new Date().toISOString()
  }));
}

export function emptyData() {
  return {
    maps: [],
    routes: [],
    historyMaps: [],
    users: [],
    counts: [],
    countsUpdatedAt: "",
    countsSourceName: "",
    countsImportWarnings: [],
    countsImportMetrics: {},
    countsImportIgnored: [],
    balanceHistory: [],
    inventoryMetrics: {},
    errors: [],
    historyEvents: [],
    notifications: [],
    metrics: {}
  };
}
