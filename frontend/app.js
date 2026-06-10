const h = React.createElement;
const APP_VERSION = "1.6.0";
const MAP_FILE_TYPES = new Set([
  "application/pdf",
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/heic",
  "image/heif",
]);
const MAP_FILE_ACCEPT = "application/pdf,.pdf,image/png,.png,image/jpeg,.jpg,.jpeg,image/webp,.webp,image/heic,.heic,image/heif,.heif";

const ROLE_OPTIONS = [
  ["separation", "Conferente de separação"],
  ["expedition", "Conferente de expedição"],
  ["stock", "Conferente de estoque"],
  ["admin", "Administrador"],
];

const TITLES = {
  overview: ["painel", "Visão geral"],
  separation: ["operação", "Separação"],
  counting: ["estoque", "Contagem"],
  conference: ["validação", "Conferência"],
  history: ["admin", "Histórico"],
  users: ["admin", "Usuários"],
};

function App() {
  const [token, setToken] = React.useState(localStorage.getItem("mnCheckToken") || localStorage.getItem("mmJavaToken") || "");
  const [theme, setTheme] = React.useState(() => {
    const saved = localStorage.getItem("mnCheckTheme");
    if (saved === "dark" || saved === "light") return saved;
    return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  });
  const [appVersion, setAppVersion] = React.useState(APP_VERSION);
  const [user, setUser] = React.useState(null);
  const [data, setData] = React.useState(emptyData());
  const [view, setView] = React.useState("overview");
  const [toast, setToast] = React.useState("");
  const [mapImportOpen, setMapImportOpen] = React.useState(false);
  const [mapImporting, setMapImporting] = React.useState(false);
  const [passwordTarget, setPasswordTarget] = React.useState(null);
  const [notificationsOpen, setNotificationsOpen] = React.useState(false);
  const [login, setLogin] = React.useState({ username: "", password: "" });
  const [newUser, setNewUser] = React.useState({ username: "", name: "", role: "separation", password: "" });
  const mapFileInputRef = React.useRef(null);
  const mapCameraInputRef = React.useRef(null);
  const unreadNotificationsRef = React.useRef(null);

  React.useEffect(() => {
    request("/api/version")
      .then((body) => setAppVersion(body.version || APP_VERSION))
      .catch(() => setAppVersion(APP_VERSION));
  }, []);

  React.useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("mnCheckTheme", theme);
  }, [theme]);

  React.useEffect(() => {
    if (!token) return;
    loadBootstrap(token).catch(() => {
      localStorage.removeItem("mnCheckToken");
      localStorage.removeItem("mmJavaToken");
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

  async function request(path, options = {}) {
    const response = await fetch(path, {
      method: options.method || "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "Operação não concluída.");
    return body;
  }

  async function loadBootstrap(activeToken = token, preferredView = view) {
    const body = await request("/api/bootstrap", { token: activeToken });
    setUser(body.user);
    setData(body);
    setAppVersion(body.version || APP_VERSION);
    setView(body.user.allowedViews.includes(preferredView) ? preferredView : body.user.allowedViews[0]);
  }

  async function selectView(nextView) {
    setView(nextView);
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
          countsImportMetrics: balances.importMetrics || {}
        }));
      }
    } catch (error) {
      notify(error.message);
    }
  }

  function notify(message) {
    setToast(message);
    window.clearTimeout(notify.timer);
    notify.timer = window.setTimeout(() => setToast(""), 2800);
  }

  async function handleLogin(event) {
    event.preventDefault();
    try {
      const body = await request("/api/login", { method: "POST", body: login });
      localStorage.setItem("mnCheckToken", body.token);
      localStorage.removeItem("mmJavaToken");
      setToken(body.token);
      setUser(body.user);
      const bootstrap = await request("/api/bootstrap", { token: body.token });
      setData(bootstrap);
      setView(body.user.allowedViews[0]);
      notify(`Login realizado para ${body.user.name}.`);
    } catch (error) {
      notify(error.message);
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
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;

    const contentType = mapContentType(file);
    if (!MAP_FILE_TYPES.has(contentType)) {
      notify("Use PDF, PNG, JPG, JPEG, WebP, HEIC ou HEIF.");
      return;
    }

    if (file.size > 10 * 1024 * 1024) {
      notify("Arquivo muito grande. Use até 10 MB neste MVP.");
      return;
    }

    try {
      setMapImporting(true);
      const dataUrl = await readFileAsDataUrl(file);
      await request("/api/maps/upload", {
        method: "POST",
        body: {
          fileName: file.name,
          contentType,
          dataUrl,
        },
      });
      setMapImportOpen(false);
      await refresh("Mapa lido pela IA e enviado para separação.", "separation");
    } catch (error) {
      notify(error.message);
    } finally {
      setMapImporting(false);
    }
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

  async function scanBarcode(mapId, code, expectedCode, source) {
    try {
      const result = await request("/api/scanner/validate", {
        method: "POST",
        body: {
          mapId,
          expectedCode,
          scannedCode: code,
          operator: user?.name || user?.username || "Operador",
          source
        },
      });
      if (result.approved) await refresh(null, "conference");
      return result;
    } catch (error) {
      notify(error.message);
      throw error;
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

  async function updateCounts(counts) {
    try {
      await request("/api/contagem", { method: "POST", body: { counts } });
      await refresh("Contagem física atualizada.", "counting");
    } catch (error) {
      notify(error.message);
      throw error;
    }
  }

  function logout() {
    localStorage.removeItem("mnCheckToken");
    localStorage.removeItem("mmJavaToken");
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
          h("img", { className: "app-logo hero-logo", src: "/logo.svg?v=3", alt: "MN - Check" }),
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
        h("button", { className: "primary-action", type: "submit" }, "Entrar")
      ),
      toast && h("div", { className: "toast" }, toast)
    );
  }

  const allowedViews = user.allowedViews || [];
  const [eyebrow, title] = TITLES[view] || TITLES[allowedViews[0]];
  const notifications = data.notifications || [];
  const unreadNotifications = notifications.filter((item) => !item.read).length;

  return h("main", { className: "dashboard-view" },
    h("aside", { className: "sidebar" },
      h("div", { className: "sidebar-brand" },
        h("img", { className: "app-logo small", src: "/logo.svg?v=3", alt: "MN - Check" }),
        h("div", null,
          h("strong", null, "MN - Check"),
          h("span", null, `${user.name} - ${user.label}`),
          h("small", { className: "sidebar-version" }, `Versão ${appVersion}`)
        )
      ),
      h("div", { className: "branch-context" }, h("strong", null, "Filial 281"), h("span", null, "Setor único de expedição")),
      user.role === "admin" && h("button", {
        className: `notification-action ${unreadNotifications ? "has-unread" : ""}`,
        onClick: () => setNotificationsOpen(!notificationsOpen)
      },
        h("span", null, "Notificações"),
        h("strong", null, unreadNotifications)
      ),
      h("nav", { className: "nav-list" },
        allowedViews.map((item) => h("button", {
          key: item,
          className: `nav-item ${view === item ? "active" : ""}`,
          onClick: () => selectView(item)
        }, TITLES[item][1]))
      ),
      h(ThemeToggle, {
        theme,
        className: "sidebar-theme-toggle",
        onToggle: () => setTheme((current) => current === "dark" ? "light" : "dark")
      }),
      h("button", { className: "ghost-action account-action", onClick: () => setPasswordTarget(user) }, "Alterar minha senha"),
      h("button", { className: "ghost-action", onClick: logout }, "Sair")
    ),
    h("section", { className: "workspace" },
      h("header", { className: "topbar" },
        h("div", null, h("p", { className: "eyebrow" }, eyebrow), h("h2", null, title)),
        h("div", { className: "topbar-actions" },
          ["admin", "separation"].includes(user.role) && view === "separation" && h("input", {
            className: "hidden",
            ref: mapFileInputRef,
            type: "file",
            accept: MAP_FILE_ACCEPT,
            onChange: uploadMapFile
          }),
          ["admin", "separation"].includes(user.role) && view === "separation" && h("input", {
            className: "hidden",
            ref: mapCameraInputRef,
            type: "file",
            accept: "image/*",
            capture: "environment",
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
        onSend: (id) => mapAction(id, "send-conference", "Mapa enviado para conferência."),
        onDelete: deleteMap
      }),
      view === "conference" && h(Conference, {
        maps: data.maps,
        onApprove: (id) => mapAction(id, "approve", "Mapa conferido sem divergência."),
        onProblem: (id) => mapAction(id, "problem", "Mapa marcado com divergência."),
        onCorrected: (id) => mapAction(id, "corrected", "Mapa corrigido e conferido."),
        onScan: scanBarcode
      }),
      view === "counting" && h(Counting, {
        counts: data.counts,
        updatedAt: data.countsUpdatedAt,
        sourceName: data.countsSourceName,
        warnings: data.countsImportWarnings,
        importMetrics: data.countsImportMetrics,
        onUpload: countUpload,
        onUpdate: updateCounts
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
      onClose: () => setMapImportOpen(false),
      onCamera: () => mapCameraInputRef.current?.click(),
      onFile: () => mapFileInputRef.current?.click()
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

function NewMapDialog({ busy, onClose, onCamera, onFile }) {
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
      h("div", { className: "map-source-grid" },
        h("button", { className: "map-source-option", disabled: busy, onClick: onCamera },
          h("strong", null, "Câmera"),
          h("span", null, "Fotografar o mapa agora")
        ),
        h("button", { className: "map-source-option", disabled: busy, onClick: onFile },
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

function Overview({ data }) {
  const metrics = data.metrics || {};
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
      metric("Mapas em separação", metrics.separating || 0),
      metric("Aguardando conferência", metrics.waiting || 0),
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

function Separation({ maps, onToggle, onSend, onDelete }) {
  const separationMaps = maps.filter((map) => map.status === "separacao");
  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Separação de mapas"), h("span", null, "marque os itens separados")),
      h("div", { className: "stack" }, separationMaps.length
        ? separationMaps.map((map) => h(MapCard, { key: map.id, map, onToggle, onSend, onDelete }))
        : empty("Nenhum mapa em separação."))
    ),
    h(QueueSummary, { maps: separationMaps, mode: "separation" })
  );
}

function Conference({ maps, onApprove, onProblem, onCorrected, onScan }) {
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
        autoStart: index === 0
      })) : empty("Nenhum mapa aguardando conferência."))
    ),
    h(QueueSummary, { maps: conferenceMaps, mode: "conference" })
  );
}

function Counting({ counts, updatedAt, sourceName, warnings = [], importMetrics = {}, onUpload, onUpdate }) {
  const [draft, setDraft] = React.useState(counts);
  const [importing, setImporting] = React.useState(false);
  const [printGeneratedAt, setPrintGeneratedAt] = React.useState(new Date());
  const fileInputRef = React.useRef(null);

  React.useEffect(() => setDraft(counts), [counts]);

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

  function changeCount(sku, value) {
    const counted = Math.max(0, Number.parseInt(value || "0", 10) || 0);
    setDraft((current) => current.map((item) => item.sku === sku ? { ...item, counted } : item));
  }

  function printCountReport() {
    setPrintGeneratedAt(new Date());
    window.setTimeout(() => window.print(), 50);
  }

  const totalSystem = draft.reduce((sum, item) => sum + item.system, 0);
  const totalCounted = draft.reduce((sum, item) => sum + item.counted, 0);
  const divergentItems = draft.filter((item) => item.counted !== item.system).length;

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
          className: "primary-action compact",
          disabled: !draft.length,
          onClick: () => onUpdate(draft)
        }, "Atualizar contagem"),
        h("button", {
          className: "secondary-action compact",
          disabled: !draft.length,
          onClick: printCountReport
        }, "Imprimir contagem")
      ),
      draft.length ? h("div", { className: "table-wrap" },
        h("table", null,
          h("thead", null, h("tr", null, h("th", null, "SKU"), h("th", null, "Sistema"), h("th", null, "Contado"), h("th", null, "Diferença"))),
          h("tbody", null, draft.map((item) => h("tr", { key: item.sku },
            h("td", null, item.sku),
            h("td", null, item.system),
            h("td", null, h("input", {
              className: "count-input",
              type: "number",
              min: "0",
              value: item.counted,
              onChange: (event) => changeCount(item.sku, event.target.value)
            })),
            h("td", null, item.counted - item.system)
          )))
        )
      ) : empty("Selecione um PDF para carregar os saldos.")
    ),
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Divergências"), h("span", null, "por SKU")),
      h("div", { className: "stack" }, draft.filter((item) => item.counted !== item.system).length
        ? draft.filter((item) => item.counted !== item.system).map((item) =>
          h("div", { className: "list-item", key: item.sku },
            h("strong", null, item.sku),
            h("span", null, `${item.counted - item.system > 0 ? "+" : ""}${item.counted - item.system} un.`)
          )
        )
        : empty("Nenhuma divergência informada."))
    ),
    h("section", { className: "count-print-sheet", "aria-hidden": "true" },
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
        h("div", null, h("span", null, "SKUs"), h("strong", null, draft.length)),
        h("div", null, h("span", null, "Saldo do sistema"), h("strong", null, totalSystem)),
        h("div", null, h("span", null, "Total contado"), h("strong", null, totalCounted)),
        h("div", null, h("span", null, "Itens divergentes"), h("strong", null, divergentItems))
      ),
      h("table", { className: "count-print-table" },
        h("thead", null,
          h("tr", null,
            h("th", null, "SKU"),
            h("th", null, "Sistema"),
            h("th", null, "Contado"),
            h("th", null, "Diferença"),
            h("th", null, "Resultado")
          )
        ),
        h("tbody", null, draft.map((item) => {
          const difference = item.counted - item.system;
          return h("tr", { key: item.sku },
            h("td", null, item.sku),
            h("td", null, item.system),
            h("td", null, item.counted),
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
  const events = (data.historyEvents || []).slice().reverse();

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

function MapCard({ map, onToggle, onSend, onDelete }) {
  const editable = map.status === "separacao";
  return h("article", { className: "order-card" },
    h("div", { className: "order-head" },
      h("div", { className: "order-title" },
        h("strong", null, `Mapa ${map.id}`),
        h("span", null, map.client),
        h("small", null, `Rota ${map.route}`)
      ),
      h("div", { className: `status-pill ${statusClass(map.status)}` }, status(map.status))
    ),
    map.attachmentName && h("div", { className: "attachment-line" }, `Arquivo importado: ${map.attachmentName}`),
    h("div", { className: "item-checks" }, map.items.map((item) =>
      h("label", { className: "check-line", key: item.sku },
        h("input", { type: "checkbox", checked: item.ok, disabled: !editable, onChange: (event) => onToggle(map.id, item.sku, event.target.checked) }),
        `${item.sku} - ${item.quantity} un. - ${item.name}`
      )
    )),
    h("div", { className: "order-actions" },
      editable
        ? [
            h("button", {
              className: "primary-action compact",
              key: "send",
              onClick: () => onSend(map.id)
            }, "Enviar para conferência"),
            h("button", {
              className: "danger-action",
              key: "delete",
              onClick: () => onDelete(map)
            }, "Apagar mapa")
          ]
        : h("span", { className: "status-note" }, "Mapa mantido no histórico desta tela.")
    )
  );
}

function ConferenceCard({ map, onApprove, onProblem, onCorrected, onScan, autoStart }) {
  const actionable = ["aguardando conferencia", "conferencia"].includes(map.status);
  const needsCorrection = map.status === "corrigir problema";
  return h("article", { className: "order-card conference-card" },
    h("div", { className: "order-head" },
      h("div", { className: "order-title" },
        h("strong", null, `Mapa ${map.id}`),
        h("span", null, map.client),
        h("small", null, `Rota ${map.route} - ${plural(map.items.length, "produto", "produtos")}`)
      ),
      h("div", { className: `status-pill ${statusClass(map.status)}` }, status(map.status))
    ),
    map.attachmentName && h("div", { className: "attachment-line" }, `Arquivo importado: ${map.attachmentName}`),
    (actionable || needsCorrection) && h(BarcodeScanner, {
      map,
      onScan,
      onApprove,
      onProblem,
      onCorrected,
      actionable,
      needsCorrection,
      autoStart
    })
  );
}

function BarcodeScanner({ map, onScan, onApprove, onProblem, onCorrected, actionable, needsCorrection, autoStart }) {
  const [manualCode, setManualCode] = React.useState("");
  const [result, setResult] = React.useState(null);
  const [scanning, setScanning] = React.useState(false);
  const [validating, setValidating] = React.useState(false);
  const [history, setHistory] = React.useState([]);
  const [lastCode, setLastCode] = React.useState("");
  const scannerRef = React.useRef(null);
  const lastScanRef = React.useRef({ code: "", at: 0 });
  const manualStartedAtRef = React.useRef(0);
  const expectedItemRef = React.useRef(null);
  const validatingRef = React.useRef(false);
  const physicalScannerRef = React.useRef({ value: "", lastKeyAt: 0 });
  const readerId = `barcode-reader-${map.id}`;
  const totalQuantity = map.items.reduce((sum, item) => sum + item.quantity, 0);
  const checkedQuantity = map.items.reduce((sum, item) => sum + (item.checkedQuantity || 0), 0);
  const remainingQuantity = Math.max(0, totalQuantity - checkedQuantity);
  const allChecked = remainingQuantity === 0;
  const expectedItem = map.items.find((item) => (item.checkedQuantity || 0) < item.quantity)
    || map.items[map.items.length - 1];
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

    const autoStartTimer = window.setTimeout(() => {
      if (active && actionable && !needsCorrection && autoStart) startScanner();
    }, 350);
    function capturePhysicalScanner(event) {
      if (["INPUT", "TEXTAREA", "SELECT"].includes(event.target?.tagName)) return;
      const current = physicalScannerRef.current;
      const now = Date.now();
      if (now - current.lastKeyAt > 120) current.value = "";
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
    function releaseCamera(event) {
      if (String(event.detail) !== String(map.id)) stopScanner();
    }
    document.addEventListener("keydown", capturePhysicalScanner);
    window.addEventListener("mncheck-camera-request", releaseCamera);
    return () => {
      active = false;
      window.clearTimeout(autoStartTimer);
      document.removeEventListener("keydown", capturePhysicalScanner);
      window.removeEventListener("mncheck-camera-request", releaseCamera);
      stopScanner();
    };
  }, [map.id]);

  async function validate(code, source = "manual") {
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
      const response = await onScan(map.id, cleanCode, currentExpected.sku, source);
      const approved = Boolean(response.approved);
      setResult({
        type: approved ? "success" : "error",
        title: approved
          ? (response.allChecked ? "Conferência concluída" : "APROVADO")
          : "BLOQUEADO",
        text: approved
          ? (response.allChecked
              ? "Todas as unidades foram lidas. Toque em OK para finalizar."
              : `${response.item.name}: ${response.item.checkedQuantity}/${response.item.quantity} unidades.`)
          : response.reason
      });
      setHistory((current) => [{
        code: response.scanned || cleanCode,
        name: approved ? response.reason : `${response.reason} - esperado ${response.expected}`,
        ok: approved,
        source,
        at: response.at || new Date().toISOString(),
      }, ...current].slice(0, 30));
      setManualCode("");
      playFeedback(approved);
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

  async function startScanner() {
    if (!window.Html5Qrcode) {
      setResult({
        type: "error",
        title: "Leitor indisponível",
        text: "Verifique a conexão com a internet ou use o código manual."
      });
      return;
    }
    if (!window.isSecureContext && location.hostname !== "localhost" && location.hostname !== "127.0.0.1") {
      setResult({
        type: "error",
        title: "A câmera precisa de HTTPS",
        text: "Use o campo manual ou acesse o endereço seguro."
      });
      return;
    }

    try {
      window.dispatchEvent(new CustomEvent("mncheck-camera-request", { detail: map.id }));
      await new Promise((resolve) => window.setTimeout(resolve, 120));
      setScanning(true);
      setResult(null);
      if (scannerRef.current) return;
      scannerRef.current = new Html5Qrcode(readerId, {
        formatsToSupport: [Html5QrcodeSupportedFormats.CODE_128],
        verbose: false
      });
      await scannerRef.current.start(
        { facingMode: "environment" },
        {
          fps: 20,
          aspectRatio: 1.777778,
          disableFlip: false,
          experimentalFeatures: { useBarCodeDetectorIfSupported: true },
          qrbox: (width, height) => ({
            width: Math.min(Math.floor(width * 0.9), 420),
            height: Math.min(Math.floor(height * 0.32), 150)
          })
        },
        (decodedText) => validate(decodedText, "camera"),
        () => {}
      );
    } catch (error) {
      setScanning(false);
      setResult({
        type: "error",
        title: "Câmera não disponível",
        text: "Não foi possível abrir a câmera traseira. Confira a permissão do navegador."
      });
    }
  }

  async function stopScanner() {
    const scanner = scannerRef.current;
    scannerRef.current = null;
    setScanning(false);
    if (!scanner) return;
    try {
      if (scanner.isScanning) await scanner.stop();
      scanner.clear();
    } catch (_) {}
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
          h("small", null, "Use a câmera ou digite o código impresso")
        )
      ),
      h("button", {
        className: `camera-action ${scanning ? "active" : ""}`,
        disabled: validating || needsCorrection,
        onClick: scanning ? stopScanner : startScanner
      }, scanning ? "Fechar câmera" : "Ler etiqueta pela câmera"),
      h("div", { className: `barcode-reader-shell ${scanning ? "active" : ""}` },
        h("div", { id: readerId, className: `barcode-reader ${scanning ? "active" : ""}` }),
        h("div", { className: "scanner-target-line", "aria-hidden": "true" })
      ),
      h("div", { className: "manual-validation" },
        h("input", {
          inputMode: "numeric",
          autoFocus: true,
          autoComplete: "off",
          placeholder: "Digite ou use o scanner USB/Bluetooth",
          value: manualCode,
          disabled: needsCorrection,
          onChange: (event) => {
            if (!manualCode) manualStartedAtRef.current = Date.now();
            setManualCode(event.target.value);
          },
          onKeyDown: (event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            const elapsed = Date.now() - manualStartedAtRef.current;
            validate(manualCode, elapsed < 700 ? "scanner" : "manual");
          }
        }),
        h("button", {
          className: "primary-action",
          disabled: needsCorrection || validating || !manualCode.replace(/\D/g, ""),
          onClick: () => validate(manualCode, "manual")
        }, validating ? "Validando..." : "Validar")
      ),
      h("p", { className: "scanner-help" },
        "A câmera lê CODE 128 continuamente. Scanners USB e Bluetooth funcionam diretamente no campo acima."
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
        h("span", null, allChecked ? "Tudo pronto para finalizar" : result?.text || "Aproxime a etiqueta da câmera ou digite o código.")
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
        actionable && h("button", {
          className: "primary-action finish-conference",
          disabled: !allChecked,
          onClick: () => onApprove(map.id)
        }, allChecked ? "OK - Finalizar conferência" : `Faltam ${remainingQuantity} unidades`),
        actionable && h("button", {
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

function voltageFromSku(sku) {
  const gradeY = String(sku || "").split(".").pop();
  return {
    "0": "Bivolt",
    "1": "127V",
    "2": "220V",
    "3": "127V",
    "4": "Bivolt",
  }[gradeY] || "Não informado";
}

function normalizeProductCode(value) {
  const digits = String(value || "").replace(/\D/g, "");
  if (digits.length !== 7) return value || "---";
  return `${digits.slice(0, 5)}.${digits[5]}.${digits[6]}`;
}

function scanSourceLabel(source) {
  return {
    camera: "câmera",
    scanner: "scanner físico",
    manual: "digitação manual"
  }[source] || "leitura";
}

async function authorizedJson(path) {
  const token = localStorage.getItem("mnCheckToken") || localStorage.getItem("mmJavaToken") || "";
  const response = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Não foi possível carregar os dados.");
  return body;
}

function playFeedback(success) {
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
            ? map.items.filter((item) => item.ok).length
            : map.items.reduce((sum, item) => sum + (item.checkedQuantity || 0), 0);
          const total = mode === "separation"
            ? map.items.length
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

function status(value) {
  return {
    separacao: "separação",
    "aguardando conferencia": "aguardando conferência",
    conferencia: "conferência",
    perfeito: "conferido",
    conferido: "conferido",
    "corrigir problema": "corrigir problema",
  }[value] || value;
}

function statusClass(value) {
  return {
    separacao: "status-open",
    "aguardando conferencia": "status-waiting",
    conferencia: "status-waiting",
    perfeito: "status-done",
    conferido: "status-done",
    "corrigir problema": "status-error",
  }[value] || "";
}

function plural(value, singular, pluralText) {
  return `${value} ${value === 1 ? singular : pluralText}`;
}

function formatDate(value) {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(value)).replace(",", "");
}

function mapContentType(file) {
  if (MAP_FILE_TYPES.has(file.type)) return file.type;
  const extension = String(file.name || "").toLowerCase().split(".").pop();
  return {
    pdf: "application/pdf",
    png: "image/png",
    jpg: "image/jpeg",
    jpeg: "image/jpeg",
    webp: "image/webp",
    heic: "image/heic",
    heif: "image/heif",
  }[extension] || "";
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error("Não foi possível ler o arquivo."));
    reader.readAsDataURL(file);
  });
}

function emptyData() {
  return {
    maps: [],
    historyMaps: [],
    users: [],
    counts: [],
    countsUpdatedAt: "",
    countsSourceName: "",
    countsImportWarnings: [],
    countsImportMetrics: {},
    errors: [],
    historyEvents: [],
    notifications: [],
    metrics: {}
  };
}

ReactDOM.createRoot(document.querySelector("#root")).render(h(App));
