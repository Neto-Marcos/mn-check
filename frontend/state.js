export const APP_VERSION = "2.3.6";
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
  ["separation", "Conferente de separação"],
  ["expedition", "Conferente de expedição"],
  ["stock", "Conferente de estoque"],
  ["admin", "Administrador"],
];

export const TITLES = {
  admin: ["controle", "Admin Marcos"],
  overview: ["painel", "Visão geral"],
  separation: ["operação", "Separação"],
  counting: ["estoque", "Contagem"],
  conference: ["validação", "Conferência"],
  history: ["admin", "Histórico"],
  users: ["admin", "Usuários"],
  settings: ["conta", "Configurações"],
};

export const FRONTEND_VIEW_IDS = ["admin", "overview", "separation", "conference", "counting", "history", "users", "settings"];
export const BOTTOM_NAV_PRIORITY = ["admin", "overview", "separation", "conference", "counting", "history", "settings"];

export function supportedAllowedViews(allowedViews = []) {
  return [...new Set((Array.isArray(allowedViews) ? allowedViews : [])
    .filter((view) => FRONTEND_VIEW_IDS.includes(view) && view !== "settings"))];
}

export function resolveFrontendView(requestedView, allowedViews = [], fallback = "overview") {
  if (requestedView === "settings") return "settings";
  const supported = supportedAllowedViews(allowedViews);
  if (supported.includes(requestedView)) return requestedView;
  if (supported.includes(fallback)) return fallback;
  return supported[0] || "overview";
}

export function isLegacyRoutesLocation(pathname, search = "") {
  const path = String(pathname || "/").replace(/\/+$/, "").toLowerCase() || "/";
  if (path === "/routes" || path.startsWith("/routes/") || path === "/rotas" || path.startsWith("/rotas/")) return true;
  try {
    return new URLSearchParams(search).get("view") === "routes";
  } catch (_) {
    return false;
  }
}

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
