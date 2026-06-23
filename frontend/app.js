import { authorizedJson, isNetworkFailure } from "./api.js";
import { clearStoredToken, readStoredToken, storeToken } from "./auth.js";
import { conferenceStatusLabel } from "./conferencia.js";
import {
  countAccounted,
  countDifference,
  hasCountMovement,
  normalizeCountRows,
  safeQuantity
} from "./contagem.js";
import { mapContentType, readFileAsDataUrl } from "./mapas.js";
import {
  normalizeInventorySku,
  normalizeProductCode,
  playFeedback,
  scanSourceLabel,
  validateBarcodeLocally,
  voltageFromSku
} from "./scanner.js";
import {
  APP_VERSION,
  BOTTOM_NAV_PRIORITY,
  MAP_FILE_ACCEPT,
  MAP_FILE_TYPES,
  OFFLINE_BOOTSTRAP,
  OFFLINE_COUNT_DRAFT,
  OFFLINE_SCAN_QUEUE,
  ROLE_OPTIONS,
  TITLES,
  emptyData,
  readOfflineScanQueue,
  readStoredJson,
  saveOfflineCountDraft
} from "./state.js";
import { formatDate, initials, plural, status, statusClass } from "./ui.js";

const React = window.React;
const ReactDOM = window.ReactDOM;
const h = React.createElement;

const ICON_PATHS = {
  overview: ["M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z"],
  separation: ["M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z", "m3.3 7 8.7 5 8.7-5", "M12 22V12"],
  conference: ["M20 6 9 17l-5-5"],
  counting: ["M3 3v18h18", "M7 16h2", "M11 12h2", "M15 8h2", "M19 5h2"],
  history: ["M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5", "M12 7v5l3 2"],
  users: ["M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z", "M22 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75"],
  settings: ["M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z", "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1.08-1.5 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.12.6.64 1 1.25 1H21a2 2 0 1 1 0 4h-.09c-.61 0-1.13.4-1.51 1Z"],
  notifications: ["M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0"],
  menu: ["M4 6h16", "M4 12h16", "M4 18h16"],
  collapse: ["m15 18-6-6 6-6"],
  expand: ["m9 18 6-6-6-6"],
  logout: ["M10 17l5-5-5-5", "M15 12H3", "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"],
  key: ["M21 2l-2 2", "M7.61 11.39a5.5 5.5 0 1 0 7.78 7.78 5.5 5.5 0 0 0-7.78-7.78Z", "m15.5 8.5 1 1L22 4l-2-2-5.5 5.5 1 1Z"],
  sun: ["M12 3V1", "M12 23v-2", "m4.22 4.22-1.42-1.42", "m21.2 21.2-1.42-1.42", "M3 12H1", "M23 12h-2", "m4.22 19.78-1.42 1.42", "m21.2 2.8-1.42 1.42", "M12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12Z"],
  moon: ["M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z"],
  user: ["M20 21a8 8 0 0 0-16 0", "M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z"],
};

function Icon({ name, size = 20 }) {
  return h("svg", {
    className: "ui-icon",
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 2,
    strokeLinecap: "round",
    strokeLinejoin: "round",
    "aria-hidden": "true"
  }, (ICON_PATHS[name] || ICON_PATHS.overview).map((path, index) =>
    h("path", { key: `${name}-${index}`, d: path })
  ));
}

function SystemClock() {
  const [now, setNow] = React.useState(new Date());
  React.useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  return h("time", { className: "system-clock", dateTime: now.toISOString() },
    now.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })
  );
}

function App() {
  const [token, setToken] = React.useState(readStoredToken());
  const [theme, setTheme] = React.useState(() => {
    const saved = localStorage.getItem("mnCheckTheme");
    if (saved === "dark" || saved === "light") return saved;
    return "dark";
  });
  const [density, setDensity] = React.useState(() => localStorage.getItem("mnCheckDensity") || "comfortable");
  const [sidebarCollapsed, setSidebarCollapsed] = React.useState(() => localStorage.getItem("mnCheckSidebar") === "collapsed");
  const [mobileNavOpen, setMobileNavOpen] = React.useState(false);
  const [authenticating, setAuthenticating] = React.useState(false);
  const [viewLoading, setViewLoading] = React.useState(false);
  const [appVersion, setAppVersion] = React.useState(APP_VERSION);
  const [online, setOnline] = React.useState(navigator.onLine);
  const [user, setUser] = React.useState(null);
  const [data, setData] = React.useState(emptyData());
  const [view, setView] = React.useState("overview");
  const [toast, setToast] = React.useState("");
  const [mapImportOpen, setMapImportOpen] = React.useState(false);
  const [mapImporting, setMapImporting] = React.useState(false);
  const [mapDraft, setMapDraft] = React.useState(null);
  const [mapDraftFiles, setMapDraftFiles] = React.useState([]);
  const [passwordTarget, setPasswordTarget] = React.useState(null);
  const [notificationsOpen, setNotificationsOpen] = React.useState(false);
  const [login, setLogin] = React.useState({ username: "", password: "" });
  const [newUser, setNewUser] = React.useState({ username: "", name: "", role: "separation", password: "" });
  const mapFileInputRef = React.useRef(null);
  const mapCameraInputRef = React.useRef(null);
  const mapUploadMetadataRef = React.useRef({ mapNumber: "", orderNumbers: [] });
  const unreadNotificationsRef = React.useRef(null);

  React.useEffect(() => {
    request("/api/version")
      .then((body) => setAppVersion(body.version || APP_VERSION))
      .catch(() => setAppVersion(APP_VERSION));
  }, []);

  React.useEffect(() => {
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js?v=194").catch(() => {});
    }
    const updateConnection = () => {
      const connected = navigator.onLine;
      setOnline(connected);
      notify(connected
        ? "Conexão restabelecida. Sincronizando dados off-line..."
        : "Você está off-line. O sistema continuará funcionando normalmente."
      );
    };
    window.addEventListener("online", updateConnection);
    window.addEventListener("offline", updateConnection);
    return () => {
      window.removeEventListener("online", updateConnection);
      window.removeEventListener("offline", updateConnection);
    };
  }, []);

  React.useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("mnCheckTheme", theme);
  }, [theme]);

  React.useEffect(() => {
    document.documentElement.dataset.density = density;
    localStorage.setItem("mnCheckDensity", density);
  }, [density]);

  React.useEffect(() => {
    localStorage.setItem("mnCheckSidebar", sidebarCollapsed ? "collapsed" : "expanded");
  }, [sidebarCollapsed]);

  React.useEffect(() => {
    if (!token) return;
    loadBootstrap(token).catch(() => {
      const cached = readStoredJson(OFFLINE_BOOTSTRAP, null);
      if (!navigator.onLine && cached?.user) {
        setUser(cached.user);
        setData(cached);
        setAppVersion(cached.version || APP_VERSION);
        setView(cached.user.allowedViews?.[0] || "overview");
        return;
      }
      clearStoredToken();
      setToken("");
    });
  }, []);

  React.useEffect(() => {
    if (!token || user?.role !== "admin") return;
    let active = true;

    async function pollNotifications() {
      try {
        const body = await request("/api/notifications");
        if (!active) return;
        const notifications = body.notifications || [];
        const unread = notifications.filter((item) => !item.read).length;
        if (unreadNotificationsRef.current !== null && unread > unreadNotificationsRef.current) {
          notify("Nova divergência encontrada. Verifique as notificações.");
        }
        unreadNotificationsRef.current = unread;
        setData((current) => ({ ...current, notifications }));
      } catch (_) {}
    }

    pollNotifications();
    const interval = window.setInterval(pollNotifications, 10000);
    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [token, user?.role]);

  React.useEffect(() => {
    if (!token || !user) return;
    const sync = () => syncOfflineData();
    window.addEventListener("online", sync);
    if (navigator.onLine) sync();
    const interval = window.setInterval(() => {
      if (navigator.onLine && (
        readOfflineScanQueue().length
        || readStoredJson(OFFLINE_COUNT_DRAFT, null)?.counts?.length
      )) sync();
    }, 15000);
    return () => {
      window.removeEventListener("online", sync);
      window.clearInterval(interval);
    };
  }, [token, user?.id]);

  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), options.timeout || 30000);
    try {
      const response = await fetch(path, {
        method: options.method || "GET",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
        },
        body: options.body ? JSON.stringify(options.body) : undefined,
        signal: controller.signal
      });
      const text = await response.text();
      const body = text ? JSON.parse(text) : {};
      if (!response.ok) throw new Error(body.error || "Operação não concluída.");
      return body;
    } catch (error) {
      if (error.name === "AbortError") {
        throw new Error("O servidor demorou para responder. Tente novamente.");
      }
      if (error instanceof SyntaxError) {
        throw new Error("O servidor retornou uma resposta inválida.");
      }
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  async function loadBootstrap(activeToken = token, preferredView = view) {
    const body = await request("/api/bootstrap", { token: activeToken });
    localStorage.setItem(OFFLINE_BOOTSTRAP, JSON.stringify(body));
    setUser(body.user);
    setData(body);
    setAppVersion(body.version || APP_VERSION);
    setView(preferredView === "settings" || body.user.allowedViews.includes(preferredView)
      ? preferredView
      : body.user.allowedViews[0]);
  }

  async function selectView(nextView) {
    setView(nextView);
    setMobileNavOpen(false);
    if (nextView === "settings") return;
    const loadsRemoteData = nextView === "history" || nextView === "counting";
    setViewLoading(loadsRemoteData);
    try {
      if (nextView === "history") {
        const history = await request("/api/historico");
        setData((current) => ({
          ...current,
          historyMaps: history.maps || [],
          errors: history.errors || [],
          historyEvents: history.events || []
        }));
      }
      if (nextView === "counting") {
        const balances = await request("/api/saldos");
        setData((current) => ({
          ...current,
          counts: balances.counts || [],
          countsUpdatedAt: balances.updatedAt || "",
          countsSourceName: balances.sourceName || "",
          countsImportWarnings: balances.warnings || [],
          countsImportMetrics: balances.importMetrics || {},
          countsImportIgnored: balances.ignoredProducts || []
        }));
      }
    } catch (error) {
      notify(error.message);
    } finally {
      setViewLoading(false);
    }
  }

  function notify(message) {
    setToast(message);
    window.clearTimeout(notify.timer);
    notify.timer = window.setTimeout(() => setToast(""), 2800);
  }

  async function handleLogin(event) {
    event.preventDefault();
    setAuthenticating(true);
    try {
      const body = await request("/api/login", { method: "POST", body: login });
      storeToken(body.token);
      setToken(body.token);
      setUser(body.user);
      const bootstrap = await request("/api/bootstrap", { token: body.token });
      setData(bootstrap);
      setView(body.user.allowedViews[0]);
      notify(`Login realizado para ${body.user.name}.`);
    } catch (error) {
      notify(error.message);
    } finally {
      setAuthenticating(false);
    }
  }

  async function refresh(message, preferredView = view) {
    await loadBootstrap(token, preferredView);
    if (message) notify(message);
  }

  async function createUser(event) {
    event.preventDefault();
    try {
      await request("/api/users", { method: "POST", body: newUser });
      setNewUser({ username: "", name: "", role: "separation", password: "" });
      await refresh("Usuário cadastrado com sucesso.", "users");
    } catch (error) {
      notify(error.message);
    }
  }

  async function removeUser(target) {
    if (target.username.toLowerCase() === "marcos") return;
    if (!window.confirm(`Remover o acesso de ${target.name}?`)) return;
    try {
      await request(`/api/users/${encodeURIComponent(target.id)}`, { method: "DELETE" });
      await refresh("Usuário removido com sucesso.", "users");
    } catch (error) {
      notify(error.message);
    }
  }

  async function changePassword(target, values) {
    const ownPassword = target.id === user.id;
    try {
      await request(`/api/users/${encodeURIComponent(target.id)}/password`, {
        method: "PATCH",
        body: {
          currentPassword: ownPassword ? values.currentPassword : "",
          newPassword: values.newPassword,
        },
      });
      setPasswordTarget(null);
      if (ownPassword) {
        logout();
        return;
      }
      notify(`Senha de ${target.name} redefinida.`);
    } catch (error) {
      throw error;
    }
  }

  async function markNotificationRead(notificationId) {
    try {
      const body = await request(`/api/notifications/${notificationId}/read`, { method: "POST" });
      const notifications = body.notifications || [];
      unreadNotificationsRef.current = notifications.filter((item) => !item.read).length;
      setData((current) => ({ ...current, notifications }));
    } catch (error) {
      notify(error.message);
    }
  }

  async function uploadMapFile(event) {
    await uploadMapFilesForReview(event);
  }

  async function uploadMapFilesForReview(event) {
    const files = Array.from(event.target.files || []);
    event.target.value = "";
    if (!files.length) return;
    try {
      setMapImporting(true);
      const uploadFiles = [];
      for (const file of files) {
        const contentType = mapContentType(file);
        if (!MAP_FILE_TYPES.has(contentType)) throw new Error("Use PDF, PNG, JPG, JPEG, WebP, HEIC ou HEIF.");
        if (file.size > 10 * 1024 * 1024) throw new Error("Cada arquivo deve ter até 10 MB.");
        uploadFiles.push({
          fileName: file.name,
          contentType,
          dataUrl: await readFileAsDataUrl(file),
        });
      }
      setMapDraftFiles(uploadFiles);
      const response = await request("/api/maps/analyze", {
        method: "POST",
        timeout: 180000,
        body: {
          files: uploadFiles,
          mapNumber: mapUploadMetadataRef.current.mapNumber,
          orderNumbers: mapUploadMetadataRef.current.orderNumbers,
        },
      });
      setMapDraft(response.draft);
      notify("IA concluiu a leitura. Revise os itens antes de salvar.");
    } catch (error) {
      notify(error.message);
    } finally {
      setMapImporting(false);
    }
  }

  async function confirmMapDraft(draft) {
    try {
      setMapImporting(true);
      await request("/api/maps/confirm", {
        method: "POST",
        body: { draft, files: mapDraftFiles },
      });
      mapUploadMetadataRef.current = { mapNumber: "", orderNumbers: [] };
      setMapDraft(null);
      setMapDraftFiles([]);
      setMapImportOpen(false);
      await refresh("Mapa revisado e enviado para separação.", "separation");
    } catch (error) {
      notify(error.message);
    } finally {
      setMapImporting(false);
    }
  }

  function openMapCamera(metadata) {
    mapUploadMetadataRef.current = metadata;
    mapCameraInputRef.current?.click();
  }

  function openMapFile(metadata = { mapNumber: "", orderNumbers: [] }) {
    mapUploadMetadataRef.current = metadata;
    mapFileInputRef.current?.click();
  }

  async function toggleItem(mapId, sku, ok) {
    try {
      await request(`/api/maps/${mapId}/items/${encodeURIComponent(sku)}`, {
        method: "PATCH",
        body: { ok },
      });
      await refresh();
    } catch (error) {
      notify(error.message);
    }
  }

  async function deleteMap(map) {
    if (!window.confirm(`Apagar o mapa ${map.id}? Esta ação não pode ser desfeita.`)) return;
    try {
      await request(`/api/maps/${map.id}`, { method: "DELETE" });
      await refresh(`Mapa ${map.id} apagado.`, "separation");
    } catch (error) {
      notify(error.message);
    }
  }

  async function scanBarcode(mapId, code, expectedCode, source, lineId) {
    try {
      const result = await request("/api/scanner/validate", {
        method: "POST",
        body: {
          mapId,
          expectedCode,
          scannedCode: code,
          operator: user?.name || user?.username || "Operador",
          source,
          lineId
        },
      });
      if (result.approved) await refresh(null, "conference");
      return result;
    } catch (error) {
      if (!navigator.onLine || error instanceof TypeError || error.message === "Failed to fetch") {
        setOnline(false);
        const local = validateBarcodeLocally(expectedCode, code);
        const queue = readOfflineScanQueue();
        queue.push({
          mapId,
          expectedCode,
          scannedCode: code,
          operator: user?.name || user?.username || "Operador",
          source,
          lineId,
          approved: local.approved,
          at: new Date().toISOString()
        });
        localStorage.setItem(OFFLINE_SCAN_QUEUE, JSON.stringify(queue.slice(-200)));
        return {
          ...local,
          offline: true,
          item: { name: "Produto validado no aparelho", checkedQuantity: 0, quantity: 0 },
          allChecked: false,
          at: new Date().toISOString()
        };
      }
      notify(error.message);
      throw error;
    }
  }

  async function scanSeparationBarcode(mapId, code, expectedCode, source, lineId) {
    const result = await request("/api/scanner/validate", {
      method: "POST",
      body: {
        mapId,
        expectedCode,
        scannedCode: code,
        operator: user?.name || user?.username || "Operador",
        source,
        lineId,
        stage: "separation"
      },
    });
    await refresh(null, "separation");
    return result;
  }

  async function syncOfflineScans() {
    const queue = readOfflineScanQueue();
    if (!queue.length || !navigator.onLine) return;
    const remaining = [];
    let synced = 0;
    let networkFailed = false;
    for (let index = 0; index < queue.length; index += 1) {
      const item = queue[index];
      try {
        const response = await fetch("/api/scanner/validate", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`
          },
          body: JSON.stringify(item)
        });
        if (!response.ok) {
          if (response.status === 401) {
            remaining.push(...queue.slice(index));
            break;
          }
          continue;
        }
        synced += 1;
      } catch (_) {
        remaining.push(...queue.slice(index));
        networkFailed = true;
        break;
      }
    }
    localStorage.setItem(OFFLINE_SCAN_QUEUE, JSON.stringify(remaining));
    if (synced) {
      notify(`${synced} leitura${synced === 1 ? "" : "s"} offline sincronizada${synced === 1 ? "" : "s"}.`);
      await refresh(null, view);
      window.dispatchEvent(new CustomEvent("mncheck-offline-synced"));
    }
    if (networkFailed) {
      throw new TypeError("A conexão ainda não está disponível para sincronizar as leituras.");
    }
  }

  async function syncOfflineCount() {
    const pending = readStoredJson(OFFLINE_COUNT_DRAFT, null);
    if (!pending?.counts?.length || !navigator.onLine) return 0;
    const response = await fetch("/api/contagem", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({ counts: pending.counts })
    });
    if (!response.ok) {
      if (response.status === 401) return 0;
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || "A contagem off-line ainda não pôde ser sincronizada.");
    }
    localStorage.removeItem(OFFLINE_COUNT_DRAFT);
    window.dispatchEvent(new CustomEvent("mncheck-count-synced"));
    return 1;
  }

  async function syncOfflineData() {
    if (!navigator.onLine || !token || !user) return;
    try {
      await syncOfflineScans();
      const countSynced = await syncOfflineCount();
      setOnline(true);
      if (countSynced) {
        await refresh("Contagem off-line sincronizada com o PostgreSQL.", "counting");
      }
    } catch (error) {
      if (isNetworkFailure(error)) setOnline(false);
      notify(error.message);
    }
  }

  async function mapAction(mapId, action, message) {
    try {
      await request(`/api/maps/${mapId}/${action}`, { method: "POST" });
      await refresh(message);
    } catch (error) {
      notify(error.message);
    }
  }

  async function countUpload(file) {
    try {
      const dataUrl = await readFileAsDataUrl(file);
      await request("/api/importar", {
        method: "POST",
        body: {
          fileName: file.name,
          contentType: file.type || "application/pdf",
          dataUrl
        },
      });
      await refresh("Saldos lidos e validados pelo PDFBox.", "counting");
    } catch (error) {
      notify(error.message);
      throw error;
    }
  }

  async function downloadBalanceDebug() {
    try {
      const response = await fetch("/api/importar/debug", {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || "Não foi possível baixar o diagnóstico.");
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "debug-importacao-saldo.txt";
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (error) {
      notify(error.message);
    }
  }

  async function updateCounts(counts) {
    try {
      await request("/api/contagem", { method: "POST", body: { counts } });
      localStorage.removeItem(OFFLINE_COUNT_DRAFT);
      await refresh("Contagem física atualizada.", "counting");
    } catch (error) {
      if (isNetworkFailure(error)) {
        setOnline(false);
        saveOfflineCountDraft(counts, user);
        setData((current) => {
          const next = { ...current, counts };
          localStorage.setItem(OFFLINE_BOOTSTRAP, JSON.stringify(next));
          return next;
        });
        notify("Você está off-line. A contagem foi salva no aparelho e será sincronizada automaticamente.");
        return { offline: true };
      }
      notify(error.message);
      throw error;
    }
  }

  async function addManualBalanceProduct(product) {
    try {
      await request("/api/saldos/produto", {
        method: "POST",
        body: product
      });
      await refresh("Produto adicionado ao saldo atual.", "counting");
    } catch (error) {
      notify(error.message);
      throw error;
    }
  }

  function saveCountDraftOffline(counts) {
    if (online && navigator.onLine) return;
    saveOfflineCountDraft(counts, user);
    setData((current) => {
      const next = { ...current, counts };
      localStorage.setItem(OFFLINE_BOOTSTRAP, JSON.stringify(next));
      return next;
    });
  }

  function logout() {
    clearStoredToken();
    setToken("");
    setUser(null);
    setData(emptyData());
  }

  if (!user) {
    return h("main", { className: "login-view" },
      h(ThemeToggle, {
        theme,
        className: "login-theme-toggle",
        onToggle: () => setTheme((current) => current === "dark" ? "light" : "dark")
      }),
      h("section", { className: "brand-panel" },
        h("div", { className: "brand-content" },
          h("img", { className: "app-logo hero-logo", src: "/logo.png?v=194", alt: "MN - Check" }),
          h("p", { className: "eyebrow" }, "conferência operacional"),
          h("h1", null, "MN - Check"),
          h("p", null, "Controle de separação, conferência e estoque."),
          h("span", { className: "version-badge" }, `Versão ${appVersion}`)
        )
      ),
      h("form", { className: "login-card", onSubmit: handleLogin },
        h("p", { className: "eyebrow" }, "acesso"),
        h("h2", null, "Entrar no MN - Check"),
        h("label", null, "Usuário",
          h("input", {
            value: login.username,
            onChange: (event) => setLogin({ ...login, username: event.target.value }),
            autoComplete: "username"
          })
        ),
        h("label", null, "Senha",
          h("input", {
            type: "password",
            value: login.password,
            onChange: (event) => setLogin({ ...login, password: event.target.value }),
            autoComplete: "current-password"
          })
        ),
        h("button", {
          className: "primary-action",
          type: "submit",
          disabled: authenticating
        }, authenticating ? "Entrando..." : "Entrar")
      ),
      toast && h("div", { className: "toast" }, toast)
    );
  }

  const allowedViews = user.allowedViews || [];
  const navigationViews = [...allowedViews, "settings"];
  const bottomNavigationViews = BOTTOM_NAV_PRIORITY
    .filter((item) => navigationViews.includes(item))
    .slice(0, 5);
  const [eyebrow, title] = TITLES[view] || TITLES[allowedViews[0]];
  const notifications = data.notifications || [];
  const unreadNotifications = notifications.filter((item) => !item.read).length;

  return h("main", {
    className: `dashboard-view ${sidebarCollapsed ? "sidebar-collapsed" : ""} ${mobileNavOpen ? "mobile-nav-open" : ""}`
  },
    h("button", {
      className: "mobile-nav-backdrop",
      "aria-label": "Fechar menu",
      onClick: () => setMobileNavOpen(false)
    }),
    h("aside", { className: "sidebar", "aria-label": "Navegação principal" },
      h("div", { className: "sidebar-brand" },
        h("img", { className: "app-logo small", src: "/logo.png?v=194", alt: "MN - Check" }),
        h("div", { className: "sidebar-brand-copy" },
          h("strong", null, "MN - Check"),
          h("small", { className: "sidebar-version" }, `Versão ${appVersion}`)
        ),
        h("button", {
          className: "sidebar-collapse-action",
          title: sidebarCollapsed ? "Expandir menu" : "Minimizar menu",
          "aria-label": sidebarCollapsed ? "Expandir menu" : "Minimizar menu",
          onClick: () => setSidebarCollapsed((current) => !current)
        }, h(Icon, { name: sidebarCollapsed ? "expand" : "collapse", size: 18 }))
      ),
      h("nav", { className: "nav-list" },
        navigationViews.map((item) => h("button", {
          key: item,
          className: `nav-item ${view === item ? "active" : ""}`,
          title: TITLES[item][1],
          "aria-current": view === item ? "page" : undefined,
          onClick: () => selectView(item)
        },
          h(Icon, { name: item }),
          h("span", { className: "nav-label" }, TITLES[item][1])
        ))
      ),
      h("button", {
        className: "sidebar-user",
        title: `${user.name} - ${user.label}`,
        onClick: () => selectView("settings")
      },
        h("span", { className: "user-avatar" }, initials(user.name)),
        h("span", { className: "sidebar-user-copy" },
          h("strong", null, user.name),
          h("small", null, user.label)
        )
      )
    ),
    h("section", { className: "workspace" },
      viewLoading && h("div", { className: "workspace-loading", role: "status", "aria-label": "Carregando dados" },
        h("span", null)
      ),
      !online && h("div", { className: "offline-banner", role: "status" },
        h("strong", null, "Você está off-line"),
        h("span", null, "Continue trabalhando normalmente. As alterações serão enviadas quando a conexão voltar.")
      ),
      h("header", { className: "topbar" },
        h("div", { className: "topbar-heading" },
          h("button", {
            className: "mobile-menu-action",
            "aria-label": "Abrir menu",
            onClick: () => setMobileNavOpen(true)
          }, h(Icon, { name: "menu", size: 22 })),
          h("div", null,
            h("p", { className: "eyebrow" }, eyebrow),
            h("h2", null, title)
          )
        ),
        h("div", { className: "topbar-actions" },
          h(SystemClock),
          h("span", { className: `connection-status ${online ? "online" : "offline"}` },
            online ? "Online" : "Modo offline"
          ),
          h("button", {
            className: "topbar-icon-action",
            title: theme === "dark" ? "Usar tema claro" : "Usar tema escuro",
            "aria-label": theme === "dark" ? "Usar tema claro" : "Usar tema escuro",
            onClick: () => setTheme(theme === "dark" ? "light" : "dark")
          }, h(Icon, { name: theme === "dark" ? "sun" : "moon", size: 18 })),
          user.role === "admin" && h("button", {
            className: `topbar-icon-action ${unreadNotifications ? "has-unread" : ""}`,
            title: "Notificações",
            "aria-label": "Abrir notificações",
            onClick: () => setNotificationsOpen(true)
          },
            h(Icon, { name: "notifications", size: 19 }),
            unreadNotifications > 0 && h("span", null, unreadNotifications)
          ),
          ["admin", "separation"].includes(user.role) && view === "separation" && h("input", {
            className: "hidden",
            ref: mapFileInputRef,
            type: "file",
            accept: MAP_FILE_ACCEPT,
            multiple: true,
            onChange: uploadMapFile
          }),
          ["admin", "separation"].includes(user.role) && view === "separation" && h("input", {
            className: "hidden",
            ref: mapCameraInputRef,
            type: "file",
            accept: "image/*",
            capture: "environment",
            multiple: true,
            onChange: uploadMapFile
          }),
          ["admin", "separation"].includes(user.role) && view === "separation" && h("button", {
            className: "primary-action compact",
            disabled: mapImporting,
            onClick: () => setMapImportOpen(true)
          }, mapImporting ? "Lendo mapa..." : "Novo mapa")
        )
      ),
      view === "overview" && h(Overview, { data }),
      view === "separation" && h(Separation, {
        maps: data.maps,
        onToggle: toggleItem,
        onScan: scanSeparationBarcode,
        onSend: (id) => mapAction(id, "send-conference", "Mapa enviado para conferência."),
        onDelete: deleteMap
      }),
      view === "conference" && h(Conference, {
        maps: data.maps,
        onApprove: (id) => mapAction(id, "approve", "Mapa conferido sem divergência."),
        onProblem: (id) => mapAction(id, "problem", "Mapa marcado com divergência."),
        onCorrected: (id) => mapAction(id, "corrected", "Mapa corrigido e conferido."),
        onScan: scanBarcode,
        onPause: (id) => mapAction(id, "pause-conference", "Conferência pausada com o progresso salvo."),
        onResume: (id) => mapAction(id, "resume-conference", "Conferência retomada."),
        onCancel: (id) => mapAction(id, "cancel-conference", "Conferência cancelada e progresso apagado.")
      }),
      view === "counting" && h(Counting, {
        counts: data.counts,
        updatedAt: data.countsUpdatedAt,
        sourceName: data.countsSourceName,
        warnings: data.countsImportWarnings,
        importMetrics: data.countsImportMetrics,
        ignoredProducts: data.countsImportIgnored,
        onUpload: countUpload,
        onDownloadDebug: downloadBalanceDebug,
        onUpdate: updateCounts,
        onAddProduct: addManualBalanceProduct,
        onOfflineDraft: saveCountDraftOffline,
        online
      }),
      view === "history" && h(History, { data }),
      view === "users" && h(Users, {
        users: data.users,
        newUser,
        setNewUser,
        createUser,
        removeUser,
        changeUserPassword: setPasswordTarget
      }),
      view === "settings" && h(Settings, {
        user,
        appVersion,
        theme,
        density,
        sidebarCollapsed,
        online,
        onThemeChange: setTheme,
        onDensityChange: setDensity,
        onSidebarPreference: setSidebarCollapsed,
        onPassword: () => setPasswordTarget(user),
        onLogout: logout
      })
    ),
    h("nav", { className: "mobile-bottom-nav", "aria-label": "Navegacao rapida" },
      bottomNavigationViews.map((item) => h("button", {
        key: `bottom-${item}`,
        className: `bottom-nav-item ${view === item ? "active" : ""}`,
        "aria-current": view === item ? "page" : undefined,
        title: TITLES[item][1],
        onClick: () => selectView(item)
      },
        h(Icon, { name: item, size: 22 }),
        h("span", null, TITLES[item][1])
      ))
    ),
    notificationsOpen && user.role === "admin" && h(NotificationPanel, {
      notifications,
      onClose: () => setNotificationsOpen(false),
      onRead: markNotificationRead,
      onOpenMap: () => {
        setView("conference");
        setNotificationsOpen(false);
      }
    }),
    mapImportOpen && h(NewMapDialog, {
      busy: mapImporting,
      draft: mapDraft,
      onClose: () => {
        setMapDraft(null);
        setMapDraftFiles([]);
        setMapImportOpen(false);
      },
      onCamera: openMapCamera,
      onFile: openMapFile,
      onConfirm: confirmMapDraft
    }),
    passwordTarget && h(PasswordDialog, {
      target: passwordTarget,
      ownPassword: passwordTarget.id === user.id,
      onClose: () => setPasswordTarget(null),
      onSave: changePassword
    }),
    toast && h("div", { className: "toast" }, toast)
  );
}

function PasswordDialog({ target, ownPassword, onClose, onSave }) {
  const [values, setValues] = React.useState({ currentPassword: "", newPassword: "", confirmation: "" });
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState("");

  async function submit(event) {
    event.preventDefault();
    setError("");
    if (values.newPassword.length < 6) {
      setError("A nova senha deve ter pelo menos 6 caracteres.");
      return;
    }
    if (values.newPassword !== values.confirmation) {
      setError("A confirmação não corresponde à nova senha.");
      return;
    }
    setSaving(true);
    try {
      await onSave(target, values);
    } catch (submitError) {
      setError(submitError.message);
      setSaving(false);
    }
  }

  return h("div", { className: "modal-backdrop", role: "presentation", onMouseDown: saving ? undefined : onClose },
    h("form", { className: "new-map-dialog password-dialog", onSubmit: submit, onMouseDown: (event) => event.stopPropagation() },
      h("div", { className: "dialog-head" },
        h("div", null,
          h("p", { className: "eyebrow" }, ownPassword ? "segurança da conta" : "administração"),
          h("h3", null, ownPassword ? "Alterar minha senha" : `Redefinir senha de ${target.name}`)
        ),
        h("button", { className: "dialog-close", type: "button", disabled: saving, onClick: onClose, "aria-label": "Fechar" }, "×")
      ),
      ownPassword && h("label", null, "Senha atual",
        h("input", {
          type: "password",
          required: true,
          autoComplete: "current-password",
          value: values.currentPassword,
          onChange: (event) => setValues({ ...values, currentPassword: event.target.value })
        })
      ),
      h("label", null, "Nova senha",
        h("input", {
          type: "password",
          required: true,
          minLength: 6,
          autoComplete: "new-password",
          value: values.newPassword,
          onChange: (event) => setValues({ ...values, newPassword: event.target.value })
        })
      ),
      h("label", null, "Confirmar nova senha",
        h("input", {
          type: "password",
          required: true,
          minLength: 6,
          autoComplete: "new-password",
          value: values.confirmation,
          onChange: (event) => setValues({ ...values, confirmation: event.target.value })
        })
      ),
      error && h("div", { className: "form-error" }, error),
      h("div", { className: "dialog-actions" },
        h("button", { className: "secondary-action compact", type: "button", disabled: saving, onClick: onClose }, "Cancelar"),
        h("button", { className: "primary-action compact", type: "submit", disabled: saving }, saving ? "Salvando..." : "Salvar nova senha")
      )
    )
  );
}

function ConferenceCancelDialog({ map, onClose, onContinue, onPause, onCancel }) {
  return h("div", { className: "modal-backdrop", role: "presentation", onMouseDown: onClose },
    h("section", {
      className: "modal-card conference-cancel-dialog",
      role: "dialog",
      "aria-modal": "true",
      "aria-labelledby": `cancel-conference-${map.id}`,
      onMouseDown: (event) => event.stopPropagation()
    },
      h("div", { className: "modal-icon warning" }, "!"),
      h("div", { className: "modal-copy" },
        h("p", { className: "eyebrow" }, `Mapa ${map.id}`),
        h("h3", { id: `cancel-conference-${map.id}` }, "Deseja realmente cancelar esta conferência?"),
        h("p", null, "Escolha se o progresso deve ser mantido para continuar depois ou apagado definitivamente.")
      ),
      h("div", { className: "modal-actions stacked" },
        h("button", { className: "danger-action", onClick: onCancel }, "Cancelar e apagar tudo"),
        h("button", { className: "secondary-action", onClick: onPause }, "Salvar progresso e sair"),
        h("button", { className: "ghost-action", onClick: onContinue }, "Continuar conferência")
      )
    )
  );
}

function NotificationPanel({ notifications, onClose, onRead, onOpenMap }) {
  return h("aside", { className: "notification-panel" },
    h("div", { className: "notification-panel-head" },
      h("div", null, h("strong", null, "Notificações"), h("span", null, "Divergências operacionais")),
      h("button", { className: "dialog-close", onClick: onClose, "aria-label": "Fechar notificações" }, "×")
    ),
    h("div", { className: "notification-list" },
      notifications.length
        ? notifications.map((item) => h("article", {
            className: `notification-item ${item.read ? "" : "unread"}`,
            key: item.id
          },
            h("div", null,
              h("strong", null, item.title),
              h("p", null, item.message),
              h("span", null, formatDate(item.at))
            ),
            h("div", { className: "notification-item-actions" },
              h("button", { className: "secondary-action compact", onClick: onOpenMap }, "Abrir mapa"),
              !item.read && h("button", {
                className: "notification-read-action",
                onClick: () => onRead(item.id)
              }, "Marcar como lida")
            )
          ))
        : empty("Nenhuma notificação.")
    )
  );
}

function NewMapDialog({ busy, draft, onClose, onCamera, onFile, onConfirm }) {
  const [mapNumber, setMapNumber] = React.useState("");
  const [ordersText, setOrdersText] = React.useState("");
  const [orderInput, setOrderInput] = React.useState("");
  const [reviewDraft, setReviewDraft] = React.useState(draft);
  const [error, setError] = React.useState("");

  React.useEffect(() => setReviewDraft(draft), [draft]);

  function metadata() {
    const cleanMapNumber = mapNumber.replace(/\D/g, "");
    const orderNumbers = Array.from(new Set(
      ordersText.split(/[\s,;]+/).map((value) => value.replace(/\D/g, "")).filter(Boolean)
    ));
    return { mapNumber: cleanMapNumber, orderNumbers };
  }

  function addOrderNumber() {
    const clean = orderInput.replace(/\D/g, "");
    if (!clean) return;
    setOrdersText((current) => current ? `${current}\n${clean}` : clean);
    setOrderInput("");
  }

  function updateDraftItem(index, field, value) {
    setReviewDraft((current) => {
      const items = [...(current.items || [])];
      items[index] = {
        ...items[index],
        [field]: field === "quantity" ? Math.max(1, Number.parseInt(value || "1", 10) || 1) : value
      };
      return { ...current, items };
    });
  }

  function removeDraftItem(index) {
    setReviewDraft((current) => ({
      ...current,
      items: (current.items || []).filter((_, itemIndex) => itemIndex !== index)
    }));
  }

  function addDraftItem() {
    setReviewDraft((current) => ({
      ...current,
      items: [...(current.items || []), { sku: "", name: "", quantity: 1, ok: false }]
    }));
  }

  function useCamera() {
    const values = metadata();
    if (!values.mapNumber) {
      setError("Informe o número do mapa.");
      return;
    }
    if (!values.orderNumbers.length) {
      setError("Informe pelo menos um número de pedido.");
      return;
    }
    setError("");
    onCamera(values);
  }

  function useFile() {
    const values = metadata();
    if (!values.mapNumber) {
      setError("Informe o número do mapa.");
      return;
    }
    if (!values.orderNumbers.length) {
      setError("Informe pelo menos um número de pedido.");
      return;
    }
    setError("");
    onFile(values);
  }

  if (reviewDraft) {
    return h("div", { className: "modal-backdrop", role: "presentation", onMouseDown: onClose },
      h("section", {
        className: "new-map-dialog map-review-dialog",
        role: "dialog",
        "aria-modal": "true",
        onMouseDown: (event) => event.stopPropagation()
      },
        h("div", { className: "dialog-head" },
          h("div", null,
            h("p", { className: "eyebrow" }, "revisao"),
            h("h3", null, `Confirmar mapa ${reviewDraft.id}`)
          ),
          h("button", { className: "dialog-close", disabled: busy, onClick: onClose, "aria-label": "Fechar" }, "x")
        ),
        h("div", { className: "map-review-meta" },
          h("label", null, "Cliente", h("input", {
            value: reviewDraft.client || "",
            onChange: (event) => setReviewDraft({ ...reviewDraft, client: event.target.value })
          })),
          h("label", null, "Rota", h("input", {
            value: reviewDraft.route || "",
            onChange: (event) => setReviewDraft({ ...reviewDraft, route: event.target.value })
          }))
        ),
        h("div", { className: "map-review-list" },
          (reviewDraft.items || []).map((item, index) => h("div", { className: "map-review-item", key: index },
            h("input", {
              value: item.sku || "",
              placeholder: "SKU",
              onChange: (event) => updateDraftItem(index, "sku", event.target.value)
            }),
            h("input", {
              value: item.name || "",
              placeholder: "Descricao",
              onChange: (event) => updateDraftItem(index, "name", event.target.value)
            }),
            h("input", {
              type: "number",
              min: "1",
              value: item.quantity || 1,
              onChange: (event) => updateDraftItem(index, "quantity", event.target.value)
            }),
            h("button", { className: "ghost-action compact", onClick: () => removeDraftItem(index) }, "Remover")
          ))
        ),
        h("div", { className: "modal-actions" },
          h("button", { className: "secondary-action compact", onClick: addDraftItem, disabled: busy }, "Adicionar item"),
          h("button", { className: "primary-action compact", disabled: busy, onClick: () => onConfirm(reviewDraft) },
            busy ? "Salvando..." : "Confirmar mapa")
        )
      )
    );
  }

  return h("div", { className: "modal-backdrop", role: "presentation", onMouseDown: onClose },
    h("section", {
      className: "new-map-dialog",
      role: "dialog",
      "aria-modal": "true",
      "aria-labelledby": "new-map-title",
      onMouseDown: (event) => event.stopPropagation()
    },
      h("div", { className: "dialog-head" },
        h("div", null,
          h("p", { className: "eyebrow" }, "entrada de documento"),
          h("h3", { id: "new-map-title" }, "Como deseja inserir o novo mapa?")
        ),
        h("button", { className: "dialog-close", disabled: busy, onClick: onClose, "aria-label": "Fechar" }, "×")
      ),
      h("div", { className: "map-manual-fields" },
        h("label", null, "Número do mapa",
          h("input", {
            inputMode: "numeric",
            placeholder: "Ex.: 15728",
            value: mapNumber,
            disabled: busy,
            onChange: (event) => setMapNumber(event.target.value)
          })
        ),
        h("label", null, "Números dos pedidos",
          h("div", { className: "order-entry-row" },
            h("input", {
              inputMode: "numeric",
              placeholder: "Digite um pedido",
              value: orderInput,
              disabled: busy,
              onChange: (event) => setOrderInput(event.target.value),
              onKeyDown: (event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  addOrderNumber();
                }
              }
            }),
            h("button", { type: "button", className: "secondary-action compact", disabled: busy, onClick: addOrderNumber }, "Adicionar")
          ),
          h("textarea", {
            rows: 3,
            inputMode: "text",
            placeholder: "Um pedido por linha",
            value: ordersText,
            disabled: busy,
            onChange: (event) => setOrdersText(event.target.value)
          }),
          h("small", null, "No iPhone, digite um pedido e toque em Adicionar. Também funciona um pedido por linha.")
        )
      ),
      error && h("div", { className: "form-error" }, error),
      h("div", { className: "map-source-grid" },
        h("button", { className: "map-source-option", disabled: busy, onClick: useCamera },
          h("strong", null, "Câmera"),
          h("span", null, "Usa os números informados e lê somente os itens da foto")
        ),
        h("button", { className: "map-source-option", disabled: busy, onClick: useFile },
          h("strong", null, "Arquivo ou imagem"),
          h("span", null, "PDF, PNG, JPG, WebP, HEIC ou HEIF")
        )
      ),
      h("button", { className: "secondary-action dialog-cancel", disabled: busy, onClick: onClose }, busy ? "Processando com IA..." : "Cancelar")
    )
  );
}

function ThemeToggle({ theme, onToggle, className = "" }) {
  const dark = theme === "dark";
  return h("button", {
    type: "button",
    className: `theme-toggle ${className}`.trim(),
    role: "switch",
    "aria-checked": dark,
    "aria-label": dark ? "Usar tema claro" : "Usar tema escuro",
    title: dark ? "Usar tema claro" : "Usar tema escuro",
    onClick: onToggle
  },
    h("span", { className: "theme-toggle-track", "aria-hidden": "true" },
      h("span", { className: "theme-toggle-thumb" })
    ),
    h("span", { className: "theme-toggle-label" }, dark ? "Tema escuro" : "Tema claro")
  );
}

function Settings({
  user,
  appVersion,
  theme,
  density,
  sidebarCollapsed,
  online,
  onThemeChange,
  onDensityChange,
  onSidebarPreference,
  onPassword,
  onLogout
}) {
  return h("div", { className: "settings-layout" },
    h("article", { className: "panel settings-profile" },
      h("div", { className: "settings-profile-head" },
        h("span", { className: "settings-avatar" }, initials(user.name)),
        h("div", null,
          h("p", { className: "eyebrow" }, "perfil de acesso"),
          h("h3", null, user.name),
          h("span", null, user.label)
        )
      ),
      h("dl", { className: "account-details" },
        h("div", null, h("dt", null, "Usuário"), h("dd", null, user.username)),
        h("div", null, h("dt", null, "Permissão"), h("dd", null, user.label)),
        h("div", null, h("dt", null, "Conexão"), h("dd", { className: online ? "text-success" : "text-warning" }, online ? "Online" : "Modo offline")),
        h("div", null, h("dt", null, "Versão"), h("dd", null, appVersion))
      ),
      h("button", { className: "secondary-action settings-password-action", onClick: onPassword },
        h(Icon, { name: "key", size: 18 }),
        h("span", null, "Alterar minha senha")
      )
    ),
    h("article", { className: "panel settings-preferences" },
      h("div", { className: "panel-header" },
        h("div", null, h("p", { className: "eyebrow" }, "preferências"), h("h3", null, "Aparência e navegação")),
        h("span", null, "salvo neste aparelho")
      ),
      h("div", { className: "preference-list" },
        h(PreferenceRow, {
          title: "Tema da interface",
          description: "Escolha o contraste mais confortável para o ambiente de trabalho."
        },
          h("div", { className: "segmented-control", role: "group", "aria-label": "Tema da interface" },
            h("button", {
              className: theme === "dark" ? "active" : "",
              onClick: () => onThemeChange("dark")
            }, h(Icon, { name: "moon", size: 17 }), h("span", null, "Escuro")),
            h("button", {
              className: theme === "light" ? "active" : "",
              onClick: () => onThemeChange("light")
            }, h(Icon, { name: "sun", size: 17 }), h("span", null, "Claro"))
          )
        ),
        h(PreferenceRow, {
          title: "Densidade das telas",
          description: "Ajusta o espaçamento sem alterar nenhuma informação."
        },
          h("div", { className: "segmented-control", role: "group", "aria-label": "Densidade das telas" },
            h("button", {
              className: density === "comfortable" ? "active" : "",
              onClick: () => onDensityChange("comfortable")
            }, "Confortável"),
            h("button", {
              className: density === "compact" ? "active" : "",
              onClick: () => onDensityChange("compact")
            }, "Compacta")
          )
        ),
        h(PreferenceRow, {
          title: "Menu lateral",
          description: "Define como a navegação deve iniciar no desktop."
        },
          h("label", { className: "setting-switch" },
            h("input", {
              type: "checkbox",
              checked: sidebarCollapsed,
              onChange: (event) => onSidebarPreference(event.target.checked)
            }),
            h("span", { "aria-hidden": "true" }),
            h("b", null, sidebarCollapsed ? "Minimizado" : "Expandido")
          )
        )
      )
    ),
    h("article", { className: "panel settings-session" },
      h("div", null,
        h("p", { className: "eyebrow" }, "sessão"),
        h("h3", null, "Encerrar acesso"),
        h("p", null, "Use esta opção ao terminar o trabalho neste aparelho.")
      ),
      h("button", { className: "logout-action", onClick: onLogout },
        h(Icon, { name: "logout", size: 18 }),
        h("span", null, "Sair do sistema")
      )
    )
  );
}

function PreferenceRow({ title, description, children }) {
  return h("div", { className: "preference-row" },
    h("div", null, h("strong", null, title), h("span", null, description)),
    children
  );
}

function Overview({ data }) {
  const metrics = data.metrics || {};
  const todayKey = new Date().toISOString().slice(0, 10);
  const todayEvents = (data.historyEvents || []).filter((event) =>
    String(event.at || "").slice(0, 10) === todayKey);
  const checkedToday = todayEvents.filter((event) =>
    ["scan_item", "approve_map", "corrected_map"].includes(event.action)).length;
  const activeConferences = (data.maps || []).filter((map) =>
    ["EM_ANDAMENTO", "PAUSADA"].includes(map.conferenceSession?.status)).length;
  const conferenceProgress = (data.maps || [])
    .filter((map) => ["aguardando conferencia", "conferencia", "corrigir problema"].includes(map.status))
    .slice(0, 5)
    .map((map) => {
      const checked = map.items.reduce((sum, item) => sum + (item.checkedQuantity || 0), 0);
      const total = map.items.reduce((sum, item) => sum + item.quantity, 0);
      return { id: map.id, checked, total, percent: total ? Math.round((checked / total) * 100) : 0 };
    });

  return h(React.Fragment, null,
    h("div", { className: "metric-grid" },
      metric("Conferências ativas", activeConferences),
      metric("Itens conferidos hoje", checkedToday),
      metric("Divergências", metrics.errorCount || 0),
      metric("Finalizados", metrics.perfect || 0)
    ),
    h("div", { className: "content-grid" },
      h("article", { className: "panel" },
        h("div", { className: "panel-header" }, h("h3", null, "Mapa operacional"), h("span", null, "tempo real")),
        h("div", { className: "flow-board" },
          flow("Filial", "281"),
          flow("Setor", "expedição central"),
          flow("Separação", plural(metrics.separating || 0, "mapa", "mapas")),
          flow("Conferência", plural(metrics.waiting || 0, "mapa", "mapas")),
          flow("Conferidos", plural(metrics.perfect || 0, "mapa", "mapas")),
          flow("Histórico de erros", `${metrics.errorCount || 0} registros`)
        )
      ),
      h("article", { className: "panel" },
        h("div", { className: "panel-header" }, h("h3", null, "Progresso das conferências"), h("span", null, "dados reais")),
        conferenceProgress.length
          ? h("div", { className: "bars" }, conferenceProgress.map((entry) =>
          h("div", { key: entry.id },
            h("div", { className: "bar-label" },
              h("span", null, `Mapa ${entry.id}`),
              h("strong", null, `${entry.checked}/${entry.total} - ${entry.percent}%`)
            ),
            h("div", { className: "bar-track" },
              h("div", { className: "bar-fill", style: { width: `${entry.percent}%` } })
            )
          )
        ))
          : empty("Nenhuma conferência em andamento.")
      )
    )
  );
}

function Separation({ maps, onToggle, onScan, onSend, onDelete }) {
  const separationMaps = maps.filter((map) => map.status === "separacao");
  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Separação de mapas"), h("span", null, "leia as unidades com o coletor")),
      h("div", { className: "stack" }, separationMaps.length
        ? separationMaps.map((map) => h(MapCard, { key: map.id, map, onToggle, onScan, onSend, onDelete }))
        : empty("Nenhum mapa em separação."))
    ),
    h(QueueSummary, { maps: separationMaps, mode: "separation" })
  );
}

function Conference({ maps, onApprove, onProblem, onCorrected, onScan, onPause, onResume, onCancel }) {
  const conferenceMaps = maps.filter((map) =>
    ["aguardando conferencia", "conferencia", "corrigir problema"].includes(map.status)
  );
  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Reconferência da expedição"), h("span", null, "mapas já separados")),
      h("div", { className: "stack" }, conferenceMaps.length ? conferenceMaps.map((map, index) => h(ConferenceCard, {
        key: map.id,
        map,
        onApprove,
        onProblem,
        onCorrected,
        onScan,
        onPause,
        onResume,
        onCancel
      })) : empty("Nenhum mapa aguardando conferência."))
    ),
    h(QueueSummary, { maps: conferenceMaps, mode: "conference" })
  );
}

function Counting({
  counts,
  updatedAt,
  sourceName,
  warnings = [],
  importMetrics = {},
  ignoredProducts = [],
  onUpload,
  onDownloadDebug,
  onUpdate,
  onAddProduct,
  onOfflineDraft,
  online
}) {
  const initialOfflineDraft = readStoredJson(OFFLINE_COUNT_DRAFT, null);
  const [draft, setDraft] = React.useState(normalizeCountRows(initialOfflineDraft?.counts || counts));
  const [importing, setImporting] = React.useState(false);
  const [savingCount, setSavingCount] = React.useState(false);
  const [offlinePending, setOfflinePending] = React.useState(Boolean(initialOfflineDraft?.counts?.length));
  const [searchCode, setSearchCode] = React.useState("");
  const [searchMessage, setSearchMessage] = React.useState("");
  const [countFilter, setCountFilter] = React.useState("all");
  const [printFilter, setPrintFilter] = React.useState("counted");
  const [manualOpen, setManualOpen] = React.useState(false);
  const [manualProduct, setManualProduct] = React.useState({ sku: "", system: "", counted: "", damaged: "", other: "" });
  const [savingManual, setSavingManual] = React.useState(false);
  const [printMode, setPrintMode] = React.useState(false);
  const [printGeneratedAt, setPrintGeneratedAt] = React.useState(new Date());
  const fileInputRef = React.useRef(null);
  const countInputRefs = React.useRef({});
  const manualSkuRef = React.useRef(null);

  React.useEffect(() => {
    const pending = readStoredJson(OFFLINE_COUNT_DRAFT, null);
    setDraft(normalizeCountRows(pending?.counts || counts));
    setOfflinePending(Boolean(pending?.counts?.length));
  }, [counts]);

  React.useEffect(() => {
    if (!manualOpen) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    window.setTimeout(() => manualSkuRef.current?.focus(), 0);
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [manualOpen]);

  React.useEffect(() => {
    const clearPending = () => setOfflinePending(false);
    window.addEventListener("mncheck-count-synced", clearPending);
    return () => window.removeEventListener("mncheck-count-synced", clearPending);
  }, []);

  React.useEffect(() => {
    const finishPrint = () => setPrintMode(false);
    window.addEventListener("afterprint", finishPrint);
    return () => window.removeEventListener("afterprint", finishPrint);
  }, []);

  async function handlePdf(event) {
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;
    if (file.type !== "application/pdf") {
      window.alert("Selecione um arquivo PDF.");
      return;
    }
    if (file.size > 25 * 1024 * 1024) {
      window.alert("O PDF deve ter no máximo 25 MB.");
      return;
    }

    setImporting(true);
    try {
      await onUpload(file);
    } catch (error) {
      window.alert(error.message);
    } finally {
      setImporting(false);
    }
  }

  function changeCountField(sku, field, value) {
    const amount = safeQuantity(value);
    setDraft((current) => {
      const next = current.map((item) => item.sku === sku ? { ...item, [field]: amount } : item);
      if (!online || !navigator.onLine) {
        onOfflineDraft(next);
        setOfflinePending(true);
      }
      return next;
    });
  }

  async function submitCount() {
    setSavingCount(true);
    try {
      const result = await onUpdate(draft);
      setOfflinePending(Boolean(result?.offline));
    } catch (_) {
      // The parent already presents validation and server errors.
    } finally {
      setSavingCount(false);
    }
  }

  async function resetCount() {
    if (!draft.length || savingCount) return;
    const hasValues = draft.some((item) => countAccounted(item) > 0);
    if (!hasValues) {
      window.alert("A contagem já está zerada.");
      return;
    }
    if (!window.confirm("Reiniciar a contagem? Tudo que foi contado, avaria e outros será zerado.")) return;

    const resetDraft = draft.map((item) => ({
      ...item,
      counted: 0,
      damaged: 0,
      other: 0
    }));
    setDraft(resetDraft);
    setSearchCode("");
    setSearchMessage("");
    setCountFilter("all");
    setSavingCount(true);
    try {
      const result = await onUpdate(resetDraft);
      setOfflinePending(Boolean(result?.offline));
    } catch (error) {
      onOfflineDraft(resetDraft);
      setOfflinePending(true);
      window.alert(error.message || "A contagem foi zerada no aparelho e será sincronizada quando possível.");
    } finally {
      setSavingCount(false);
    }
  }

  async function submitManualProduct(event) {
    event.preventDefault();
    const sku = normalizeInventorySku(manualProduct.sku);
    const system = Number.parseInt(manualProduct.system || "0", 10);
    const counted = Number.parseInt(manualProduct.counted || "0", 10);
    const damaged = Number.parseInt(manualProduct.damaged || "0", 10);
    const other = Number.parseInt(manualProduct.other || "0", 10);
    if (!/^\d{4,8}\.\d{1,3}\.\d{1,3}$/.test(sku)) {
      window.alert("Informe o SKU no formato produto.gradeX.gradeY. Exemplo: 76331.3.4.");
      return;
    }
    if ([system, counted, damaged, other].some((value) => value < 0 || Number.isNaN(value))) {
      window.alert("Informe quantidades válidas.");
      return;
    }
    setSavingManual(true);
    try {
      await onAddProduct({ sku, system, counted, damaged, other });
      setManualProduct({ sku: "", system: "", counted: "", damaged: "", other: "" });
      setManualOpen(false);
    } catch (error) {
      window.alert(error.message);
    } finally {
      setSavingManual(false);
    }
  }

  function findBalanceCode(value, focus = true) {
    const digits = String(value || "").replace(/\D/g, "");
    setSearchCode(value);
    if (!digits) {
      setSearchMessage("");
      return null;
    }
    const match = draft.find((item) => String(item.sku).replace(/\D/g, "") === digits);
    if (!match) {
      setSearchMessage(`Código ${value} não encontrado na lista de saldo.`);
      playFeedback(false);
      return null;
    }
    setSearchCode(match.sku);
    setSearchMessage(`Encontrado: ${match.sku} - saldo do sistema ${match.system}.`);
    playFeedback(true);
    if (focus) {
      window.setTimeout(() => {
        countInputRefs.current[match.sku]?.focus();
        countInputRefs.current[match.sku]?.select();
      }, 80);
    }
    return match;
  }

  function printCountReport() {
    setPrintGeneratedAt(new Date());
    setPrintMode(true);
    window.setTimeout(() => window.print(), 80);
    window.setTimeout(() => setPrintMode(false), 2_000);
  }

  const countedItems = draft.filter(hasCountMovement);
  const compliantItems = draft.filter((item) => hasCountMovement(item) && countDifference(item) === 0);
  const divergentRows = draft.filter((item) => countDifference(item) !== 0);
  const pendingItems = draft.filter((item) => !hasCountMovement(item));
  const totalSystem = draft.reduce((sum, item) => sum + item.system, 0);
  const totalCounted = draft.reduce((sum, item) => sum + item.counted, 0);
  const totalDamaged = draft.reduce((sum, item) => sum + item.damaged, 0);
  const totalOther = draft.reduce((sum, item) => sum + item.other, 0);
  const totalAccounted = draft.reduce((sum, item) => sum + countAccounted(item), 0);
  const divergentItems = divergentRows.length;
  const searchDigits = searchCode.replace(/\D/g, "");
  const filteredDraft = draft.filter((item) => {
    if (countFilter === "counted") return hasCountMovement(item);
    if (countFilter === "ok") return hasCountMovement(item) && countDifference(item) === 0;
    if (countFilter === "divergent") return countDifference(item) !== 0;
    if (countFilter === "pending") return !hasCountMovement(item);
    return true;
  });
  const visibleDraft = searchDigits
    ? filteredDraft.filter((item) => String(item.sku).replace(/\D/g, "").includes(searchDigits))
    : filteredDraft;
  const printableDraft = printFilter === "counted" ? countedItems : visibleDraft;
  const printTotalSystem = printableDraft.reduce((sum, item) => sum + item.system, 0);
  const printTotalCounted = printableDraft.reduce((sum, item) => sum + item.counted, 0);
  const printTotalDamaged = printableDraft.reduce((sum, item) => sum + item.damaged, 0);
  const printTotalOther = printableDraft.reduce((sum, item) => sum + item.other, 0);
  const printTotalAccounted = printableDraft.reduce((sum, item) => sum + countAccounted(item), 0);
  const printDivergentItems = printableDraft.filter((item) => countDifference(item) !== 0).length;

  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" },
        h("h3", null, "Contagem de estoque"),
        h("span", null, updatedAt ? `Saldo atualizado em ${formatDate(updatedAt)}` : "Saldo ainda não importado")
      ),
      updatedAt && h("div", { className: "count-import-summary" },
        h("div", null,
          h("span", { className: "count-import-label" }, "Última importação"),
          h("strong", null, sourceName || "PDF de saldo")
        ),
        h("div", null,
          h("span", { className: "count-import-label" }, "Resultado"),
          h("strong", null, `${counts.length} SKUs processados pelo PDFBox`)
        )
      ),
      updatedAt && h("div", { className: "count-import-metrics" },
        h("div", null, h("span", null, "Folhas"), h("strong", null, importMetrics.pagesProcessed || 0)),
        h("div", null, h("span", null, "Linhas lidas"), h("strong", null, importMetrics.totalLinesRead || 0)),
        h("div", null, h("span", null, "SKUs"), h("strong", null, importMetrics.skusRead || counts.length)),
        h("div", null, h("span", null, "Ignoradas"), h("strong", null, importMetrics.ignoredLines || 0)),
        h("div", null, h("span", null, "Duplicados"), h("strong", null, importMetrics.duplicateSkus || 0)),
        h("div", null, h("span", null, "Conflitos"), h("strong", null, importMetrics.conflictsFound || 0)),
        h("div", null, h("span", null, "Tempo"), h("strong", null, `${importMetrics.elapsedMs || 0} ms`))
      ),
      warnings.length > 0 && h("div", { className: "count-import-warnings" },
        h("strong", null, "Avisos da importação"),
        h("ul", null, warnings.map((warning) => h("li", { key: warning }, warning)))
      ),
      updatedAt && h("div", { className: "count-import-diagnostics" },
        h("div", { className: "count-import-diagnostics-head" },
          h("div", null,
            h("strong", null, "Diagnóstico da importação"),
            h("span", null, `${ignoredProducts.length} linhas detalhadas`)
          ),
          h("button", {
            className: "secondary-action compact",
            onClick: onDownloadDebug
          }, "Baixar debug")
        ),
        ignoredProducts.length > 0 && h("details", null,
          h("summary", null, "Ver linhas ignoradas e motivos"),
          h("div", { className: "ignored-products-list" },
            ignoredProducts.map((item, index) => h("div", {
              className: "ignored-product",
              key: `${item.page}-${index}-${item.line}`
            },
              h("div", null,
                h("strong", null, item.product ? `Produto ${item.product}` : `Folha ${item.page}`),
                h("span", null, item.reason)
              ),
              h("code", null, item.line)
            ))
          )
        )
      ),
      offlinePending && h("div", { className: "offline-count-pending" },
        h("strong", null, "Contagem salva no aparelho"),
        h("span", null, online
          ? "Aguardando confirmação do servidor."
          : "Será enviada automaticamente quando a internet voltar.")
      ),
      h("input", {
        className: "hidden",
        ref: fileInputRef,
        type: "file",
        accept: "application/pdf,.pdf",
        onChange: handlePdf
      }),
      h("div", { className: "count-actions" },
        h("button", {
          className: "secondary-action compact",
          disabled: importing,
          onClick: () => fileInputRef.current?.click()
        }, importing ? "Lendo todas as folhas..." : "Selecionar PDF de saldo"),
        h("button", {
          className: "secondary-action compact",
          onClick: () => setManualOpen(true)
        }, "Adicionar produto"),
        h("button", {
          className: "primary-action compact",
          disabled: !draft.length || savingCount,
          onClick: submitCount
        }, savingCount
          ? "Salvando..."
          : online ? "Atualizar contagem" : "Salvar contagem off-line"),
        h("button", {
          className: "secondary-action compact",
          disabled: !draft.length || savingCount,
          onClick: resetCount
        }, "Reiniciar contagem"),
        h("button", {
          className: "secondary-action compact",
          disabled: !draft.length,
          onClick: printCountReport
        }, printFilter === "counted" ? "Imprimir contados" : "Imprimir filtro")
      ),
      draft.length && h("section", { className: "count-status-filter" },
        h("div", { className: "count-status-filter-head" },
          h("strong", null, "Acompanhamento da contagem"),
          h("span", null, `${compliantItems.length} corretos · ${divergentItems} divergentes · ${pendingItems.length} pendentes`)
        ),
        h("div", { className: "count-total-strip" },
          h("div", { className: "count-total-card" }, h("span", null, "Sistema"), h("strong", null, totalSystem)),
          h("div", { className: "count-total-card" }, h("span", null, "Contado"), h("strong", null, totalCounted)),
          h("div", { className: "count-total-card" }, h("span", null, "Avaria"), h("strong", null, totalDamaged)),
          h("div", { className: "count-total-card" }, h("span", null, "Outros"), h("strong", null, totalOther)),
          h("div", { className: "count-total-card emphasis" }, h("span", null, "Apurado"), h("strong", null, totalAccounted))
        ),
        h("div", { className: "count-filter-grid" },
          [
            ["all", "Todos", draft.length],
            ["counted", "Já contados", countedItems.length],
            ["ok", "Conformes", compliantItems.length],
            ["divergent", "Divergentes", divergentItems],
            ["pending", "Pendentes", pendingItems.length]
          ].map(([value, label, amount]) => h("button", {
            key: value,
            className: `count-filter-chip ${countFilter === value ? "active" : ""}`,
            onClick: () => setCountFilter(value)
          }, h("span", null, label), h("strong", null, amount)))
        ),
        h("label", { className: "print-scope-control" },
          h("span", null, "Impressão"),
          h("select", {
            value: printFilter,
            onChange: (event) => setPrintFilter(event.target.value)
          },
            h("option", { value: "counted" }, "Somente produtos contados"),
            h("option", { value: "visible" }, "Somente filtro atual")
          )
        )
      ),
      draft.length && h("section", { className: "balance-search" },
        h("div", { className: "balance-search-head" },
          h("div", null,
            h("strong", null, "Localizar produto no saldo"),
            h("span", null, "Use o coletor/bipador ou digite o código")
          ),
          h("b", null, `${visibleDraft.length}/${draft.length} SKUs`)
        ),
        h("div", { className: "balance-search-controls" },
          h("input", {
            inputMode: "numeric",
            autoFocus: true,
            autoComplete: "off",
            placeholder: "Código da etiqueta ou SKU",
            value: searchCode,
            onChange: (event) => {
              setSearchCode(event.target.value);
              setSearchMessage("");
            },
            onKeyDown: (event) => {
              if (event.key !== "Enter") return;
              event.preventDefault();
              findBalanceCode(searchCode);
            }
          }),
          h("button", {
            className: "primary-action compact",
            disabled: !searchDigits,
            onClick: () => findBalanceCode(searchCode)
          }, "Pesquisar"),
          searchCode && h("button", {
            className: "ghost-action compact",
            onClick: () => {
              setSearchCode("");
              setSearchMessage("");
            }
          }, "Limpar")
        ),
        searchMessage && h("div", {
          className: `balance-search-message ${searchMessage.startsWith("Encontrado") ? "success" : ""}`
        }, searchMessage)
      ),
      visibleDraft.length ? h("div", { className: "table-wrap count-table-wrap" },
        h("table", null,
          h("thead", null, h("tr", null,
            h("th", null, "SKU"),
            h("th", null, "Sistema"),
            h("th", null, "Contado"),
            h("th", null, "Avaria"),
            h("th", null, "Outros"),
            h("th", null, "Diferença")
          )),
          h("tbody", null, visibleDraft.map((item) => h("tr", {
            key: item.sku,
            className: [
              hasCountMovement(item) && countDifference(item) === 0 ? "count-row-ok" : "",
              countDifference(item) !== 0 ? "count-row-divergent" : "",
              searchDigits && String(item.sku).replace(/\D/g, "") === searchDigits ? "count-row-found" : ""
            ].filter(Boolean).join(" ")
          },
            h("td", null, item.sku),
            h("td", null, item.system),
            h("td", null, h("input", {
              className: "count-input",
              type: "number",
              min: "0",
              value: item.counted,
              ref: (element) => {
                if (element) countInputRefs.current[item.sku] = element;
              },
              onChange: (event) => changeCountField(item.sku, "counted", event.target.value)
            })),
            h("td", null, h("input", {
              className: "count-input",
              type: "number",
              min: "0",
              value: item.damaged,
              onChange: (event) => changeCountField(item.sku, "damaged", event.target.value)
            })),
            h("td", null, h("input", {
              className: "count-input",
              type: "number",
              min: "0",
              value: item.other,
              onChange: (event) => changeCountField(item.sku, "other", event.target.value)
            })),
            h("td", { className: countDifference(item) === 0 ? "diff-ok" : "diff-alert" }, countDifference(item))
          )))
        )
      ) : draft.length
        ? empty("Nenhum SKU corresponde à pesquisa.")
        : empty("Selecione um PDF para carregar os saldos.")
    ),
    manualOpen && ReactDOM.createPortal(h("div", {
      className: "modal-backdrop",
      role: "presentation",
      onMouseDown: (event) => {
        if (event.target === event.currentTarget) setManualOpen(false);
      }
    },
      h("form", { className: "modal-card manual-product-modal", onSubmit: submitManualProduct },
        h("div", { className: "modal-head" },
          h("div", null,
            h("span", null, "AJUSTE DE SALDO"),
            h("h3", null, "Adicionar produto")
          ),
          h("button", {
            type: "button",
            className: "icon-button",
            onClick: () => setManualOpen(false),
            "aria-label": "Fechar"
          }, "×")
        ),
        h("p", { className: "modal-text" },
          "Use esta opção quando o PDF não trouxe um item corretamente. O produto será salvo no PostgreSQL e aparecerá para todos."
        ),
        h("label", null,
          h("span", null, "SKU"),
          h("input", {
            ref: manualSkuRef,
            value: manualProduct.sku,
            placeholder: "76331.3.4",
            inputMode: "numeric",
            onChange: (event) => setManualProduct((current) => ({ ...current, sku: event.target.value }))
          })
        ),
        h("div", { className: "form-grid count-reason-grid" },
          h("label", null,
            h("span", null, "Saldo do sistema"),
            h("input", {
              type: "number",
              min: "0",
              value: manualProduct.system,
              onChange: (event) => setManualProduct((current) => ({ ...current, system: event.target.value }))
            })
          ),
          h("label", null,
            h("span", null, "Quantidade contada"),
            h("input", {
              type: "number",
              min: "0",
              value: manualProduct.counted,
              onChange: (event) => setManualProduct((current) => ({ ...current, counted: event.target.value }))
            })
          ),
          h("label", null,
            h("span", null, "Avaria"),
            h("input", {
              type: "number",
              min: "0",
              value: manualProduct.damaged,
              onChange: (event) => setManualProduct((current) => ({ ...current, damaged: event.target.value }))
            })
          ),
          h("label", null,
            h("span", null, "Outros"),
            h("input", {
              type: "number",
              min: "0",
              value: manualProduct.other,
              onChange: (event) => setManualProduct((current) => ({ ...current, other: event.target.value }))
            })
          )
        ),
        h("div", { className: "modal-actions" },
          h("button", {
            type: "button",
            className: "ghost-action compact",
            onClick: () => setManualOpen(false)
          }, "Cancelar"),
          h("button", {
            type: "submit",
            className: "primary-action compact",
            disabled: savingManual
          }, savingManual ? "Salvando..." : "Salvar produto")
        )
      )
    ), document.body),
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Divergências"), h("span", null, "por SKU")),
      h("div", { className: "stack" }, divergentRows.length
        ? divergentRows.map((item) =>
          h("div", { className: "list-item", key: item.sku },
            h("strong", null, item.sku),
            h("span", null, `${countDifference(item) > 0 ? "+" : ""}${countDifference(item)} un.`)
          )
        )
        : empty("Nenhuma divergência informada."))
    ),
    printMode && h("section", { className: "count-print-sheet", "aria-hidden": "true" },
      h("header", { className: "count-print-header" },
        h("div", null,
          h("span", null, "MN - Check"),
          h("h1", null, "Relatório de contagem de estoque"),
          h("p", null, "Resultado da contagem física comparado ao saldo do sistema")
        ),
        h("strong", null, `Versão ${APP_VERSION}`)
      ),
      h("div", { className: "count-print-meta" },
        h("div", null, h("span", null, "Arquivo de saldo"), h("strong", null, sourceName || "Não informado")),
        h("div", null, h("span", null, "Saldo importado em"), h("strong", null, updatedAt ? formatDate(updatedAt) : "Não informado")),
        h("div", null, h("span", null, "Relatório emitido em"), h("strong", null, formatDate(printGeneratedAt)))
      ),
      h("div", { className: "count-print-totals" },
        h("div", null, h("span", null, "SKUs"), h("strong", null, printableDraft.length)),
        h("div", null, h("span", null, "Saldo do sistema"), h("strong", null, printTotalSystem)),
        h("div", null, h("span", null, "Total contado"), h("strong", null, printTotalCounted)),
        h("div", null, h("span", null, "Avaria"), h("strong", null, printTotalDamaged)),
        h("div", null, h("span", null, "Outros"), h("strong", null, printTotalOther)),
        h("div", null, h("span", null, "Total apurado"), h("strong", null, printTotalAccounted)),
        h("div", null, h("span", null, "Itens divergentes"), h("strong", null, printDivergentItems))
      ),
      h("table", { className: "count-print-table" },
        h("colgroup", null,
          h("col", { className: "print-col-sku" }),
          h("col", { className: "print-col-qty" }),
          h("col", { className: "print-col-qty" }),
          h("col", { className: "print-col-qty" }),
          h("col", { className: "print-col-qty" }),
          h("col", { className: "print-col-qty" }),
          h("col", { className: "print-col-diff" }),
          h("col", { className: "print-col-status" })
        ),
        h("thead", null,
          h("tr", null,
            h("th", null, "SKU"),
            h("th", null, "Sistema"),
            h("th", null, "Cont."),
            h("th", null, "Avaria"),
            h("th", null, "Outros"),
            h("th", null, "Apurado"),
            h("th", null, "Dif."),
            h("th", null, "Status")
          )
        ),
        h("tbody", null, printableDraft.map((item) => {
          const accounted = countAccounted(item);
          const difference = countDifference(item);
          return h("tr", { key: item.sku },
            h("td", null, item.sku),
            h("td", null, item.system),
            h("td", null, item.counted),
            h("td", null, item.damaged),
            h("td", null, item.other),
            h("td", null, accounted),
            h("td", null, difference > 0 ? `+${difference}` : difference),
            h("td", null, difference === 0 ? "Conforme" : "Divergente")
          );
        }))
      ),
      h("footer", { className: "count-print-signatures" },
        h("div", null, h("span", null, "Responsável pela contagem")),
        h("div", null, h("span", null, "Responsável pela validação"))
      )
    )
  );
}

function Users({ users, newUser, setNewUser, createUser, removeUser, changeUserPassword }) {
  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Cadastrar login"), h("span", null, "admin")),
      h("form", { className: "stack", onSubmit: createUser },
        h("div", { className: "form-row" },
          h("input", { placeholder: "Usuário", value: newUser.username, onChange: (e) => setNewUser({ ...newUser, username: e.target.value }) }),
          h("input", { placeholder: "Nome", value: newUser.name, onChange: (e) => setNewUser({ ...newUser, name: e.target.value }) })
        ),
        h("div", { className: "form-row" },
          h("select", { value: newUser.role, onChange: (e) => setNewUser({ ...newUser, role: e.target.value }) },
            ROLE_OPTIONS.map(([value, label]) => h("option", { key: value, value }, label))
          ),
          h("input", { placeholder: "Senha", type: "password", value: newUser.password, onChange: (e) => setNewUser({ ...newUser, password: e.target.value }) })
        ),
        h("button", { className: "primary-action compact", type: "submit" }, "Cadastrar usuário")
      )
    ),
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Usuários cadastrados"), h("span", null, `${users.length} ativos`)),
      h("div", { className: "stack" }, users.map((user) =>
        h("div", { className: "user-card", key: user.id },
          h("div", { className: "user-card-info" },
            h("strong", null, user.username),
            h("span", null, `${user.name} - ${user.label}`)
          ),
          h("div", { className: "user-card-actions" },
            h("button", {
              className: "password-user-action",
              onClick: () => changeUserPassword(user)
            }, "Alterar senha"),
            user.username.toLowerCase() === "marcos"
              ? h("span", { className: "protected-user" }, "Administrador principal")
              : h("button", {
                  className: "remove-user-action",
                  onClick: () => removeUser(user)
                }, "Remover usuário")
          )
        )
      ))
    )
  );
}

function History({ data }) {
  const mapHistory = (data.historyMaps || data.maps)
    .filter((map) => ["aguardando conferencia", "conferencia", "corrigir problema", "perfeito", "conferido"].includes(map.status))
    .slice()
    .sort((a, b) => Number(b.id) - Number(a.id));
  const events = (data.historyEvents || [])
    .slice()
    .sort((left, right) => String(right.at || "").localeCompare(String(left.at || "")));

  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Divergências e correções"), h("span", null, "histórico")),
      h("div", { className: "stack" }, data.errors.length ? data.errors.map((item) =>
        h("div", { className: "list-item", key: `${item.order}-${item.issue}` }, h("strong", null, `Mapa ${item.order}`), h("span", null, `${item.issue} - ${item.owner}`))
      ) : empty("Nenhum erro registrado."))
    ),
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Histórico de mapas"), h("span", null, `${mapHistory.length} registros`)),
      h("div", { className: "stack" }, mapHistory.length ? mapHistory.map((map) =>
        h("div", { className: "list-item", key: map.id },
          h("strong", null, `Mapa ${map.id} - ${map.client}`),
          h("span", null, `${status(map.status)} - Rota ${map.route}`)
        )
      ) : empty("Nenhum mapa no histórico."))
    ),
    h("article", { className: "panel history-events-panel" },
      h("div", { className: "panel-header" },
        h("h3", null, "Movimentações"),
        h("span", null, `${events.length} registros`)
      ),
      h("div", { className: "stack" }, events.length ? events.map((event, index) =>
        h("div", { className: "list-item", key: `${event.at}-${index}` },
          h("strong", null, event.description),
          h("span", null, `${event.userName} - ${formatDate(event.at)}`)
        )
      ) : empty("Nenhuma movimentação registrada."))
    )
  );
}

function MapCard({ map, onToggle, onScan, onSend, onDelete }) {
  const editable = map.status === "separacao";
  const totalQuantity = map.items.reduce((sum, item) => sum + item.quantity, 0);
  const checkedQuantity = map.items.reduce((sum, item) => sum + Math.min(item.quantity, item.checkedQuantity || 0), 0);
  const allSeparated = totalQuantity > 0 && checkedQuantity >= totalQuantity;

  return h("article", { className: "order-card conference-card separation-card" },
    h("div", { className: "order-head" },
      h("div", { className: "order-title" },
        h("strong", null, `Mapa ${map.id}`),
        h("span", null, map.client),
        h("small", null, `Rota ${map.route} - ${plural(map.items.length, "produto", "produtos")}`)
      ),
      h("div", { className: `status-pill ${statusClass(map.status)}` }, status(map.status))
    ),
    map.attachmentName && h("div", { className: "attachment-line" }, `Arquivo importado: ${map.attachmentName}`),
    map.orderNumbers?.length && h("div", { className: "order-numbers-line" },
      h("strong", null, "Pedidos"),
      h("span", null, map.orderNumbers.join(", "))
    ),
    editable && h(SeparationScanner, { map, onScan, onSend, onDelete, allSeparated }),
    !editable && h("span", { className: "status-note" }, "Mapa mantido no histórico desta tela.")
  );
}

function SeparationScanner({ map, onScan, onSend, onDelete, allSeparated }) {
  const [manualCode, setManualCode] = React.useState("");
  const [result, setResult] = React.useState(null);
  const [validating, setValidating] = React.useState(false);
  const [history, setHistory] = React.useState([]);
  const [lastCode, setLastCode] = React.useState("");
  const codeInputRef = React.useRef(null);
  const lastScanRef = React.useRef({ code: "", at: 0 });
  const manualStartedAtRef = React.useRef(0);
  const validatingRef = React.useRef(false);
  const physicalScannerRef = React.useRef({ value: "", lastKeyAt: 0 });
  const totalQuantity = map.items.reduce((sum, item) => sum + item.quantity, 0);
  const checkedQuantity = map.items.reduce((sum, item) => sum + Math.min(item.quantity, item.checkedQuantity || 0), 0);
  const remainingQuantity = Math.max(0, totalQuantity - checkedQuantity);
  const expectedItem = map.items.find((item) => (item.checkedQuantity || 0) < item.quantity) || map.items[map.items.length - 1];

  React.useEffect(() => {
    let active = true;
    authorizedJson(`/api/scanner/history?mapId=${encodeURIComponent(map.id)}&limit=20`)
      .then((body) => {
        if (!active) return;
        setHistory((body.history || []).map((entry) => ({
          code: entry.scanned,
          name: entry.reason,
          ok: entry.approved,
          source: entry.source,
          at: entry.at
        })));
      })
      .catch(() => {});
    const focusTimer = window.setTimeout(() => codeInputRef.current?.focus(), 180);
    function capturePhysicalScanner(event) {
      if (["INPUT", "TEXTAREA", "SELECT"].includes(event.target?.tagName)) return;
      const current = physicalScannerRef.current;
      const now = Date.now();
      if (now - current.lastKeyAt > 250) current.value = "";
      current.lastKeyAt = now;
      if (event.key === "Enter") {
        if (current.value.replace(/\D/g, "").length === 7) {
          event.preventDefault();
          validate(current.value, "scanner");
        }
        current.value = "";
        return;
      }
      if (/^[\d .-]$/.test(event.key)) current.value += event.key;
    }
    document.addEventListener("keydown", capturePhysicalScanner);
    return () => {
      active = false;
      window.clearTimeout(focusTimer);
      document.removeEventListener("keydown", capturePhysicalScanner);
    };
  }, [map.id, checkedQuantity]);

  async function validate(code, source = "manual") {
    const cleanCode = String(code || "").replace(/\D/g, "");
    if (!cleanCode || validatingRef.current) {
      if (!cleanCode) setResult({ type: "error", title: "Código obrigatório", text: "Digite ou escaneie um código para validar." });
      return;
    }
    if (!expectedItem || allSeparated) return;
    const now = Date.now();
    if (lastScanRef.current.code === cleanCode && now - lastScanRef.current.at < 1000) return;
    lastScanRef.current = { code: cleanCode, at: now };
    validatingRef.current = true;
    setValidating(true);
    setLastCode(cleanCode);
    try {
      const response = await onScan(map.id, cleanCode, expectedItem.sku, source, expectedItem.lineId);
      const approved = Boolean(response.approved);
      setResult({
        type: approved ? "success" : "error",
        title: approved ? (response.allChecked ? "Separação concluída" : "APROVADO") : "BLOQUEADO",
        text: approved
          ? (response.allChecked ? "Todas as unidades foram lidas. Envie para conferência." : `${response.item.name}: ${response.item.checkedQuantity}/${response.item.quantity} unidades.`)
          : response.reason
      });
      setHistory((current) => [{
        code: response.scanned || cleanCode,
        name: approved ? response.reason : `${response.reason} - esperado ${response.expected}`,
        ok: approved,
        source,
        at: response.at || new Date().toISOString(),
      }, ...current].slice(0, 20));
      setManualCode("");
      playFeedback(approved);
      window.setTimeout(() => codeInputRef.current?.focus(), 80);
    } catch (error) {
      setResult({ type: "error", title: "Código não confere", text: error.message });
      setHistory((current) => [{
        code: cleanCode,
        name: error.message,
        ok: false,
        source,
        at: new Date().toISOString(),
      }, ...current].slice(0, 20));
      playFeedback(false);
    } finally {
      validatingRef.current = false;
      setValidating(false);
    }
  }

  const resultTitle = allSeparated ? "Separação concluída" : result?.title || "Aguardando leitura";
  const resultType = allSeparated ? "success" : result?.type || "waiting";

  return h("div", { className: "conference-flow separation-flow" },
    h("section", { className: "conference-step scan-step" },
      h("div", { className: "conference-step-title" },
        h("span", null, "1"),
        h("div", null,
          h("strong", null, "Leitura da separação"),
          h("small", null, "Use o coletor/bipador para separar unidade por unidade")
        )
      ),
      h("div", { className: "manual-validation" },
        h("input", {
          ref: codeInputRef,
          inputMode: "numeric",
          autoFocus: true,
          autoComplete: "off",
          enterKeyHint: "done",
          spellCheck: false,
          placeholder: "Bipe com o coletor ou digite o código",
          value: manualCode,
          disabled: allSeparated || validating,
          onChange: (event) => {
            if (!manualCode) manualStartedAtRef.current = Date.now();
            setManualCode(event.target.value);
          },
          onKeyDown: (event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            const elapsed = Date.now() - manualStartedAtRef.current;
            validate(manualCode, elapsed < 1800 ? "scanner" : "manual");
          }
        }),
        h("button", {
          className: "primary-action",
          disabled: allSeparated || validating || !manualCode.replace(/\D/g, ""),
          onClick: () => validate(manualCode, "manual")
        }, validating ? "Validando..." : "Validar")
      ),
      h("p", { className: "scanner-help" }, "O coletor funciona como teclado: bipar a etiqueta preenche o código e envia com Enter.")
    ),
    h("section", { className: "conference-step result-step" },
      h("div", { className: "conference-step-title" },
        h("span", null, "2"),
        h("div", null,
          h("strong", null, "Resultado da separação"),
          h("small", null, `${checkedQuantity} de ${totalQuantity} unidades separadas`)
        )
      ),
      h("div", { className: `conference-result ${resultType}` },
        h("strong", null, resultTitle),
        h("span", null, allSeparated ? "Tudo pronto para enviar" : result?.text || "Bipe a próxima etiqueta para continuar.")
      ),
      h("div", { className: "conference-progress" },
        h("div", { style: { width: `${totalQuantity ? Math.round((checkedQuantity / totalQuantity) * 100) : 0}%` } })
      ),
      expectedItem && h("div", { className: "expected-details" },
        detailRow(allSeparated ? "Último código esperado" : "Próximo código esperado", normalizeProductCode(expectedItem.sku)),
        detailRow("Produto", expectedItem.name),
        detailRow("SKU", expectedItem.sku),
        detailRow("Voltagem esperada", voltageFromSku(expectedItem.sku)),
        detailRow("Quantidade", `${expectedItem.checkedQuantity || 0}/${expectedItem.quantity}`),
        detailRow("Produto lido", lastCode ? normalizeProductCode(lastCode) : "---")
      ),
      h("div", { className: "conference-final-actions" },
        h("button", {
          className: "primary-action finish-conference",
          disabled: !allSeparated,
          onClick: () => onSend(map.id)
        }, allSeparated ? "Enviar para conferência" : `Faltam ${remainingQuantity} unidades`),
        h("button", { className: "danger-action", onClick: () => onDelete(map) }, "Apagar mapa")
      )
    ),
    h("section", { className: "conference-step history-step" },
      h("div", { className: "conference-step-title simple" },
        h("div", null,
          h("strong", null, "Histórico"),
          h("small", null, `${history.length} leituras registradas`)
        )
      ),
      history.length
        ? h("div", { className: "scan-history" }, history.map((entry, index) =>
            h("div", { className: entry.ok ? "success" : "error", key: `${entry.at}-${index}` },
              h("span", null, entry.ok ? "OK" : "!"),
              h("div", null,
                h("strong", null, entry.code),
                h("small", null, `${entry.name} - ${scanSourceLabel(entry.source)}`)
              ),
              h("time", null, new Date(entry.at).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" }))
            )
          ))
        : h("p", { className: "empty-history" }, "Nenhuma separação realizada ainda.")
    )
  );
}
function ConferenceCard({ map, onApprove, onProblem, onCorrected, onScan, onPause, onResume, onCancel }) {
  const [cancelOpen, setCancelOpen] = React.useState(false);
  const actionable = ["aguardando conferencia", "conferencia"].includes(map.status);
  const needsCorrection = map.status === "corrigir problema";
  const session = map.conferenceSession || {};
  const paused = session.status === "PAUSADA";
  return h("article", { className: "order-card conference-card" },
    h("div", { className: "order-head" },
      h("div", { className: "order-title" },
        h("strong", null, `Mapa ${map.id}`),
        h("span", null, map.client),
        h("small", null, `Rota ${map.route} - ${plural(map.items.length, "produto", "produtos")}`)
      ),
      h("div", { className: `status-pill ${statusClass(map.status)}` }, status(map.status))
    ),
    session.status && h("div", { className: `conference-session-banner ${paused ? "paused" : ""}` },
      h("div", null,
        h("strong", null, conferenceStatusLabel(session.status)),
        h("span", null, `${session.operator || "Operador"} · atualizado ${formatDate(session.updatedAt)}`)
      ),
      h("b", null, `${session.progress || 0}%`)
    ),
    map.attachmentName && h("div", { className: "attachment-line" }, `Arquivo importado: ${map.attachmentName}`),
    map.orderNumbers?.length && h("div", { className: "order-numbers-line" },
      h("strong", null, "Pedidos"),
      h("span", null, map.orderNumbers.join(", "))
    ),
    (actionable || needsCorrection) && h(BarcodeScanner, {
      map,
      onScan,
      onApprove,
      onProblem,
      onCorrected,
      actionable,
      needsCorrection,
      paused,
      onPause: () => onPause(map.id),
      onResume: () => onResume(map.id),
      onCancel: () => setCancelOpen(true)
    }),
    cancelOpen && h(ConferenceCancelDialog, {
      map,
      onClose: () => setCancelOpen(false),
      onContinue: () => setCancelOpen(false),
      onPause: async () => {
        await onPause(map.id);
        setCancelOpen(false);
      },
      onCancel: async () => {
        await onCancel(map.id);
        setCancelOpen(false);
      }
    })
  );
}

function BarcodeScanner({
  map,
  onScan,
  onApprove,
  onProblem,
  onCorrected,
  actionable,
  needsCorrection,
  paused,
  onPause,
  onResume,
  onCancel
}) {
  const [manualCode, setManualCode] = React.useState("");
  const [result, setResult] = React.useState(null);
  const [validating, setValidating] = React.useState(false);
  const [history, setHistory] = React.useState([]);
  const [lastCode, setLastCode] = React.useState("");
  const [offlineProgress, setOfflineProgress] = React.useState(0);
  const codeInputRef = React.useRef(null);
  const lastScanRef = React.useRef({ code: "", at: 0 });
  const manualStartedAtRef = React.useRef(0);
  const expectedItemRef = React.useRef(null);
  const validatingRef = React.useRef(false);
  const physicalScannerRef = React.useRef({ value: "", lastKeyAt: 0 });
  const totalQuantity = map.items.reduce((sum, item) => sum + item.quantity, 0);
  const serverCheckedQuantity = map.items.reduce((sum, item) => sum + (item.checkedQuantity || 0), 0);
  const checkedQuantity = Math.min(totalQuantity, serverCheckedQuantity + offlineProgress);
  const remainingQuantity = Math.max(0, totalQuantity - checkedQuantity);
  const allChecked = remainingQuantity === 0;
  let pendingOfflineUnits = offlineProgress;
  const expectedItem = map.items.find((item) => {
    const serverChecked = item.checkedQuantity || 0;
    const available = Math.max(0, item.quantity - serverChecked);
    const consumedOffline = Math.min(available, pendingOfflineUnits);
    pendingOfflineUnits -= consumedOffline;
    return serverChecked + consumedOffline < item.quantity;
  }) || map.items[map.items.length - 1];
  expectedItemRef.current = expectedItem;

  React.useEffect(() => {
    let active = true;
    authorizedJson(`/api/scanner/history?mapId=${encodeURIComponent(map.id)}&limit=30`)
      .then((body) => {
        if (!active) return;
        setHistory((body.history || []).map((entry) => ({
          code: entry.scanned,
          name: entry.reason,
          ok: entry.approved,
          source: entry.source,
          at: entry.at
        })));
      })
      .catch(() => {});

    const focusTimer = window.setTimeout(() => codeInputRef.current?.focus(), 250);
    function capturePhysicalScanner(event) {
      if (["INPUT", "TEXTAREA", "SELECT"].includes(event.target?.tagName)) return;
      const current = physicalScannerRef.current;
      const now = Date.now();
      if (now - current.lastKeyAt > 250) current.value = "";
      current.lastKeyAt = now;
      if (event.key === "Enter") {
        if (current.value.replace(/\D/g, "").length === 7) {
          event.preventDefault();
          validate(current.value, "scanner");
        }
        current.value = "";
        return;
      }
      if (/^[\d .-]$/.test(event.key)) current.value += event.key;
    }
    function resetOfflineProgress() {
      setOfflineProgress(0);
    }
    document.addEventListener("keydown", capturePhysicalScanner);
    window.addEventListener("mncheck-offline-synced", resetOfflineProgress);
    return () => {
      active = false;
      window.clearTimeout(focusTimer);
      document.removeEventListener("keydown", capturePhysicalScanner);
      window.removeEventListener("mncheck-offline-synced", resetOfflineProgress);
    };
  }, [map.id]);

  async function validate(code, source = "manual") {
    if (paused) {
      setResult({ type: "error", title: "Conferência pausada", text: "Retome a conferência antes de bipar novas etiquetas." });
      return;
    }
    const cleanCode = String(code || "").replace(/\D/g, "");
    if (!cleanCode || validatingRef.current) {
      if (!cleanCode) {
        setResult({ type: "error", title: "Código obrigatório", text: "Digite ou escaneie um código para validar." });
      }
      return;
    }
    const now = Date.now();
    if (lastScanRef.current.code === cleanCode && now - lastScanRef.current.at < 1000) return;
    lastScanRef.current = { code: cleanCode, at: now };

    const currentExpected = expectedItemRef.current;
    if (!currentExpected) return;
    validatingRef.current = true;
    setValidating(true);
    setLastCode(cleanCode);
    try {
      const response = await onScan(map.id, cleanCode, currentExpected.sku, source, currentExpected.lineId);
      const approved = Boolean(response.approved);
      const completedOffline = response.offline && approved && remainingQuantity <= 1;
      if (response.offline && approved) setOfflineProgress((current) => current + 1);
      setResult({
        type: approved ? "success" : "error",
        title: approved
          ? (response.offline
              ? (completedOffline ? "Conferência concluída offline" : "APROVADO OFFLINE")
              : (response.allChecked ? "Conferência concluída" : "APROVADO"))
          : "BLOQUEADO",
        text: approved
          ? (response.offline
              ? "Etiqueta validada no aparelho. A leitura será sincronizada quando a internet voltar."
              : response.allChecked
              ? "Todas as unidades foram lidas. Toque em OK para finalizar."
              : `${response.item.name}: ${response.item.checkedQuantity}/${response.item.quantity} unidades.`)
          : response.reason
      });
      setHistory((current) => [{
        code: response.scanned || cleanCode,
        name: response.offline
          ? `${response.reason} - aguardando sincronização`
          : approved ? response.reason : `${response.reason} - esperado ${response.expected}`,
        ok: approved,
        source,
        at: response.at || new Date().toISOString(),
      }, ...current].slice(0, 30));
      setManualCode("");
      playFeedback(approved);
      window.setTimeout(() => codeInputRef.current?.focus(), 80);
    } catch (error) {
      setResult({ type: "error", title: "Código não confere", text: error.message });
      setHistory((current) => [{
        code: cleanCode,
        name: error.message,
        ok: false,
        source,
        at: new Date().toISOString(),
      }, ...current].slice(0, 30));
      playFeedback(false);
    } finally {
      validatingRef.current = false;
      setValidating(false);
    }
  }

  const resultTitle = allChecked
    ? "Conferência concluída"
    : result?.title || "Aguardando leitura";
  const resultType = allChecked ? "success" : result?.type || "waiting";

  return h("div", { className: "conference-flow" },
    h("section", { className: "conference-step scan-step" },
      h("div", { className: "conference-step-title" },
        h("span", null, "1"),
        h("div", null,
          h("strong", null, "Leitura da etiqueta"),
          h("small", null, "Use o coletor/bipador ou digite o código")
        )
      ),
      h("div", { className: "manual-validation" },
        h("input", {
          ref: codeInputRef,
          inputMode: "numeric",
          autoFocus: true,
          autoComplete: "off",
          enterKeyHint: "done",
          spellCheck: false,
          placeholder: "Bipe com o coletor ou digite o código",
          value: manualCode,
          disabled: needsCorrection || paused,
          onChange: (event) => {
            if (!manualCode) manualStartedAtRef.current = Date.now();
            setManualCode(event.target.value);
          },
          onKeyDown: (event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            const elapsed = Date.now() - manualStartedAtRef.current;
            validate(manualCode, elapsed < 1800 ? "scanner" : "manual");
          }
        }),
        h("button", {
          className: "primary-action",
          disabled: needsCorrection || paused || validating || !manualCode.replace(/\D/g, ""),
          onClick: () => validate(manualCode, "manual")
        }, validating ? "Validando..." : "Validar")
      ),
      h("p", { className: "scanner-help" },
        "O coletor USB ou Bluetooth funciona como teclado: bipar a etiqueta preenche o código e envia com Enter."
      )
    ),
    h("section", { className: "conference-step result-step" },
      h("div", { className: "conference-step-title" },
        h("span", null, "2"),
        h("div", null,
          h("strong", null, "Resultado da conferência"),
          h("small", null, `${checkedQuantity} de ${totalQuantity} unidades conferidas`)
        )
      ),
      h("div", { className: `conference-result ${resultType}` },
        h("strong", null, resultTitle),
        h("span", null, allChecked ? "Tudo pronto para finalizar" : result?.text || "Bipe a etiqueta com o coletor ou digite o código.")
      ),
      h("div", { className: "conference-progress" },
        h("div", { style: { width: `${totalQuantity ? Math.round((checkedQuantity / totalQuantity) * 100) : 0}%` } })
      ),
      expectedItem && h("div", { className: "expected-details" },
        detailRow(
          allChecked ? "Último código esperado" : "Próximo código esperado",
          normalizeProductCode(expectedItem.sku)
        ),
        detailRow("Produto", expectedItem.name),
        detailRow("SKU", expectedItem.sku),
        detailRow("Voltagem esperada", voltageFromSku(expectedItem.sku)),
        detailRow("Quantidade", `${expectedItem.checkedQuantity || 0}/${expectedItem.quantity}`),
        detailRow("Produto lido", lastCode ? normalizeProductCode(lastCode) : "---")
      ),
      h("div", { className: "conference-final-actions" },
        paused && h("button", {
          className: "primary-action",
          onClick: onResume
        }, "Retomar conferência"),
        actionable && !paused && checkedQuantity > 0 && h("button", {
          className: "secondary-action",
          onClick: onPause
        }, "Salvar progresso e sair"),
        actionable && h("button", {
          className: "ghost-action",
          onClick: onCancel
        }, "Cancelar conferência"),
        actionable && h("button", {
          className: "primary-action finish-conference",
          disabled: paused || !allChecked || offlineProgress > 0,
          onClick: () => onApprove(map.id)
        }, offlineProgress > 0
          ? "Aguardando internet para finalizar"
          : allChecked ? "OK - Finalizar conferência" : `Faltam ${remainingQuantity} unidades`),
        actionable && !paused && h("button", {
          className: "danger-action",
          onClick: () => onProblem(map.id)
        }, "Informar divergência"),
        needsCorrection && h("button", {
          className: "primary-action finish-conference",
          onClick: () => onCorrected(map.id)
        }, "OK - Problema corrigido")
      )
    ),
    h("section", { className: "conference-step history-step" },
      h("div", { className: "conference-step-title simple" },
        h("div", null,
          h("strong", null, "Histórico"),
          h("small", null, `${history.length} leituras registradas`)
        )
      ),
      history.length
        ? h("div", { className: "scan-history" }, history.map((entry, index) =>
            h("div", { className: entry.ok ? "success" : "error", key: `${entry.at}-${index}` },
              h("span", null, entry.ok ? "OK" : "!"),
              h("div", null,
                h("strong", null, entry.code),
                h("small", null, `${entry.name} - ${scanSourceLabel(entry.source)}`)
              ),
              h("time", null, new Date(entry.at).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" }))
            )
          ))
        : h("p", { className: "empty-history" }, "Nenhuma conferência realizada ainda.")
    )
  );
}

function detailRow(label, value) {
  return h("div", { className: "detail-row", key: label },
    h("span", null, label),
    h("strong", null, value)
  );
}

function QueueSummary({ maps, mode }) {
  const activeStatuses = mode === "separation"
    ? ["separacao"]
    : ["corrigir problema", "aguardando conferencia", "conferencia"];
  const queue = maps
    .filter((map) => activeStatuses.includes(map.status))
    .sort((a, b) => {
      if (a.status === "corrigir problema" && b.status !== "corrigir problema") return -1;
      if (b.status === "corrigir problema" && a.status !== "corrigir problema") return 1;
      return Number(a.id) - Number(b.id);
    })
    .slice(0, 8);

  return h("article", { className: "panel" },
    h("div", { className: "panel-header" },
      h("h3", null, mode === "separation" ? "Fila de separação" : "Fila de conferência"),
      h("span", null, `${queue.length} ${queue.length === 1 ? "mapa ativo" : "mapas ativos"}`)
    ),
    queue.length
      ? h("div", { className: "queue-list" }, queue.map((map) => {
          const checked = mode === "separation"
            ? map.items.reduce((sum, item) => sum + Math.min(item.quantity, item.checkedQuantity || 0), 0)
            : map.items.reduce((sum, item) => sum + (item.checkedQuantity || 0), 0);
          const total = mode === "separation"
            ? map.items.reduce((sum, item) => sum + item.quantity, 0)
            : map.items.reduce((sum, item) => sum + item.quantity, 0);
          const percent = total ? Math.round((checked / total) * 100) : 0;
          return h("div", { className: `queue-item ${map.status === "corrigir problema" ? "urgent" : ""}`, key: map.id },
            h("div", { className: "queue-item-head" },
              h("div", null, h("strong", null, `Mapa ${map.id}`), h("span", null, map.client)),
              h("b", null, `${checked}/${total}`)
            ),
            h("div", { className: "queue-progress" }, h("div", { style: { width: `${percent}%` } })),
            h("div", { className: "queue-meta" },
              h("span", null, status(map.status)),
              h("span", null, `${percent}% concluído`)
            )
          );
        }))
      : empty(mode === "separation" ? "Nenhum mapa aguardando separação." : "Nenhum mapa aguardando conferência.")
  );
}

function metric(label, value) {
  return h("article", { className: "metric-card" }, h("span", null, label), h("strong", null, value));
}

function flow(title, meta) {
  return h("article", { className: "flow-card" }, h("strong", null, title), h("span", null, meta));
}

function empty(message) {
  return h("div", { className: "list-item" }, h("strong", null, message), h("span", null, "Os registros aparecerão aqui."));
}

class AppErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error("MN_CHECK_UI_ERROR", error, info);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return h("main", { className: "fatal-error" },
      h("img", { className: "app-logo", src: "/logo.png?v=194", alt: "MN - Check" }),
      h("p", { className: "eyebrow" }, "Falha de interface"),
      h("h1", null, "Não foi possível concluir esta operação"),
      h("p", null, "Seus dados persistidos não foram apagados. Recarregue a tela para continuar."),
      h("button", { className: "primary-action", onClick: () => window.location.reload() }, "Recarregar sistema")
    );
  }
}

ReactDOM.createRoot(document.querySelector("#root")).render(
  h(AppErrorBoundary, null, h(App))
);
