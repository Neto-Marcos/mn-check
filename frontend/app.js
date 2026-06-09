const h = React.createElement;

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
  history: ["admin", "Auditoria e histórico"],
  users: ["admin", "Usuários"],
};

function App() {
  const [token, setToken] = React.useState(localStorage.getItem("mnCheckToken") || localStorage.getItem("mmJavaToken") || "");
  const [user, setUser] = React.useState(null);
  const [data, setData] = React.useState(emptyData());
  const [view, setView] = React.useState("overview");
  const [toast, setToast] = React.useState("");
  const [mapImportOpen, setMapImportOpen] = React.useState(false);
  const [mapImporting, setMapImporting] = React.useState(false);
  const [passwordTarget, setPasswordTarget] = React.useState(null);
  const [login, setLogin] = React.useState({ username: "", password: "" });
  const [newUser, setNewUser] = React.useState({ username: "", name: "", role: "separation", password: "" });
  const mapPdfInputRef = React.useRef(null);
  const mapCameraInputRef = React.useRef(null);

  React.useEffect(() => {
    if (!token) return;
    loadBootstrap(token).catch(() => {
      localStorage.removeItem("mnCheckToken");
      localStorage.removeItem("mmJavaToken");
      setToken("");
    });
  }, []);

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
    setView(body.user.allowedViews.includes(preferredView) ? preferredView : body.user.allowedViews[0]);
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

  async function uploadMapFile(event) {
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;

    if (!["application/pdf", "image/png", "image/jpeg"].includes(file.type)) {
      notify("Use apenas PDF, PNG ou JPG.");
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
          contentType: file.type,
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

  async function scanBarcode(mapId, code) {
    try {
      const result = await request(`/api/maps/${mapId}/scan`, {
        method: "POST",
        body: { code },
      });
      await refresh(null, "conference");
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

  async function countUpload(file, counts) {
    try {
      const dataUrl = await readFileAsDataUrl(file);
      await request("/api/counts/upload", {
        method: "POST",
        body: { fileName: file.name, dataUrl, counts },
      });
      await refresh(`${counts.length} saldos importados do PDF.`, "counting");
    } catch (error) {
      notify(error.message);
      throw error;
    }
  }

  async function updateCounts(counts) {
    try {
      await request("/api/counts", { method: "PATCH", body: { counts } });
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
      h("section", { className: "brand-panel" },
        h("img", { className: "app-logo hero-logo", src: "/logo.svg?v=3", alt: "MN - Check" }),
        h("p", { className: "eyebrow" }, "conferência operacional"),
        h("h1", null, "MN - Check"),
        h("p", null, "Controle de mapas de carga, separação, expedição, contagem e auditoria para eletrodomésticos e eletroportáteis."),
        h("div", { className: "login-stats" },
          h("span", null, h("strong", null, "Java"), " backend"),
          h("span", null, h("strong", null, "React"), " frontend"),
          h("span", null, h("strong", null, "281"), " filial")
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

  return h("main", { className: "dashboard-view" },
    h("aside", { className: "sidebar" },
      h("div", { className: "sidebar-brand" },
        h("img", { className: "app-logo small", src: "/logo.svg?v=3", alt: "MN - Check" }),
        h("div", null, h("strong", null, "MN - Check"), h("span", null, `${user.name} - ${user.label}`))
      ),
      h("div", { className: "branch-context" }, h("strong", null, "Filial 281"), h("span", null, "Setor único de expedição")),
      h("nav", { className: "nav-list" },
        allowedViews.map((item) => h("button", {
          key: item,
          className: `nav-item ${view === item ? "active" : ""}`,
          onClick: () => setView(item)
        }, TITLES[item][1]))
      ),
      h("button", { className: "ghost-action account-action", onClick: () => setPasswordTarget(user) }, "Alterar minha senha"),
      h("button", { className: "ghost-action", onClick: logout }, "Sair")
    ),
    h("section", { className: "workspace" },
      h("header", { className: "topbar" },
        h("div", null, h("p", { className: "eyebrow" }, eyebrow), h("h2", null, title)),
        h("div", { className: "topbar-actions" },
          user.role === "admin" && h("input", {
            className: "hidden",
            ref: mapPdfInputRef,
            type: "file",
            accept: "application/pdf,.pdf",
            onChange: uploadMapFile
          }),
          user.role === "admin" && h("input", {
            className: "hidden",
            ref: mapCameraInputRef,
            type: "file",
            accept: "image/*",
            capture: "environment",
            onChange: uploadMapFile
          }),
          user.role === "admin" && h("button", {
            className: "primary-action compact",
            disabled: mapImporting,
            onClick: () => setMapImportOpen(true)
          }, mapImporting ? "Lendo mapa..." : "Novo mapa")
        )
      ),
      view === "overview" && h(Overview, { data }),
      view === "separation" && h(Separation, { maps: data.maps, onToggle: toggleItem, onSend: (id) => mapAction(id, "send-conference", "Mapa enviado para conferência.") }),
      view === "conference" && h(Conference, {
        maps: data.maps,
        onApprove: (id) => mapAction(id, "approve", "Mapa conferido sem divergência."),
        onProblem: (id) => mapAction(id, "problem", "Mapa marcado com divergência."),
        onCorrected: (id) => mapAction(id, "corrected", "Mapa corrigido e conferido."),
        onScan: scanBarcode
      }),
      view === "counting" && h(Counting, { counts: data.counts, onUpload: countUpload, onUpdate: updateCounts }),
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
    mapImportOpen && h(NewMapDialog, {
      busy: mapImporting,
      onClose: () => setMapImportOpen(false),
      onCamera: () => mapCameraInputRef.current?.click(),
      onPdf: () => mapPdfInputRef.current?.click()
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

function NewMapDialog({ busy, onClose, onCamera, onPdf }) {
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
        h("button", { className: "map-source-option", disabled: busy, onClick: onPdf },
          h("strong", null, "PDF"),
          h("span", null, "Selecionar um arquivo do dispositivo")
        )
      ),
      h("button", { className: "secondary-action dialog-cancel", disabled: busy, onClick: onClose }, busy ? "Processando com IA..." : "Cancelar")
    )
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

function Separation({ maps, onToggle, onSend }) {
  const separationMaps = maps.filter((map) => map.status === "separacao");
  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Separação de mapas"), h("span", null, "marque os itens separados")),
      h("div", { className: "stack" }, separationMaps.length ? separationMaps.map((map) => h(MapCard, { key: map.id, map, onToggle, onSend })) : empty("Nenhum mapa em separação."))
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
      h("div", { className: "stack" }, conferenceMaps.length ? conferenceMaps.map((map) => h(ConferenceCard, { key: map.id, map, onApprove, onProblem, onCorrected, onScan })) : empty("Nenhum mapa aguardando conferência."))
    ),
    h(QueueSummary, { maps: conferenceMaps, mode: "conference" })
  );
}

function Counting({ counts, onUpload, onUpdate }) {
  const [draft, setDraft] = React.useState(counts);
  const [importing, setImporting] = React.useState(false);
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
    if (file.size > 10 * 1024 * 1024) {
      window.alert("O PDF deve ter no máximo 10 MB.");
      return;
    }

    setImporting(true);
    try {
      const rows = await extractCountsFromPdf(file);
      await onUpload(file, rows);
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

  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Contagem de estoque"), h("span", null, "saldo atualizado")),
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
        }, importing ? "Lendo PDF..." : "Selecionar PDF de saldo"),
        h("button", {
          className: "primary-action compact",
          disabled: !draft.length,
          onClick: () => onUpdate(draft)
        }, "Atualizar contagem")
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
  return h("div", { className: "section-grid" },
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Divergências e correções"), h("span", null, "histórico")),
      h("div", { className: "stack" }, data.errors.length ? data.errors.map((item) =>
        h("div", { className: "list-item", key: `${item.order}-${item.issue}` }, h("strong", null, `Mapa ${item.order}`), h("span", null, `${item.issue} - ${item.owner}`))
      ) : empty("Nenhum erro registrado."))
    ),
    h("article", { className: "panel" },
      h("div", { className: "panel-header" }, h("h3", null, "Auditoria"), h("span", null, "ações recentes")),
      h("div", { className: "stack" }, data.auditLog.length ? data.auditLog.slice().reverse().map((item, index) =>
        h("div", { className: "list-item", key: index }, h("strong", null, item.userName), h("span", null, `${item.description} - ${formatDate(item.at)}`))
      ) : empty("Nenhuma ação registrada."))
    )
  );
}

function MapCard({ map, onToggle, onSend }) {
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
        ? h("button", { className: "primary-action compact", onClick: () => onSend(map.id) }, "Enviar para conferência")
        : h("span", { className: "status-note" }, "Mapa mantido no histórico desta tela.")
    )
  );
}

function ConferenceCard({ map, onApprove, onProblem, onCorrected, onScan }) {
  const actionable = ["aguardando conferencia", "conferencia"].includes(map.status);
  const needsCorrection = map.status === "corrigir problema";
  const allChecked = map.items.every((item) => (item.checkedQuantity || 0) >= item.quantity);
  return h("article", { className: "order-card" },
    h("div", { className: "order-head" },
      h("div", { className: "order-title" },
        h("strong", null, `Mapa ${map.id}`),
        h("span", null, map.client),
        h("small", null, plural(map.items.length, "item", "itens"))
      ),
      h("div", { className: `status-pill ${statusClass(map.status)}` }, status(map.status))
    ),
    map.attachmentName && h("div", { className: "attachment-line" }, `Arquivo importado: ${map.attachmentName}`),
    (actionable || needsCorrection) && h(BarcodeScanner, { map, onScan }),
    h("div", { className: "order-actions" },
      actionable
        ? [
            h("button", {
              className: "primary-action compact",
              key: "approve",
              disabled: !allChecked,
              title: allChecked ? "Finalizar conferência" : "Confira todas as unidades para finalizar",
              onClick: () => onApprove(map.id)
            }, allChecked ? "Conferido" : "Conferência incompleta"),
            h("button", { className: "danger-action", key: "problem", onClick: () => onProblem(map.id) }, "Não, corrigir")
          ]
        : needsCorrection
          ? h("button", { className: "primary-action compact corrected-action", onClick: () => onCorrected(map.id) }, "Já corrigido")
          : h("span", { className: "status-note" }, "Mapa mantido no histórico desta tela.")
    )
  );
}

function BarcodeScanner({ map, onScan }) {
  const [manualCode, setManualCode] = React.useState("");
  const [result, setResult] = React.useState(null);
  const [scanning, setScanning] = React.useState(false);
  const scannerRef = React.useRef(null);
  const readerId = `barcode-reader-${map.id}`;

  React.useEffect(() => () => stopScanner(), []);

  async function validate(code) {
    const cleanCode = String(code || "").replace(/\D/g, "");
    if (!cleanCode) return;
    try {
      const response = await onScan(map.id, cleanCode);
      setResult({
        type: "success",
        text: `${response.item.name} confirmado (${response.item.checkedQuantity}/${response.item.quantity})`
      });
      setManualCode("");
      playFeedback(true);
    } catch (error) {
      setResult({ type: "error", text: error.message });
      playFeedback(false);
    }
  }

  async function startScanner() {
    if (!window.Html5Qrcode) {
      setResult({ type: "error", text: "Leitor indisponível. Verifique a conexão com a internet." });
      return;
    }
    if (!window.isSecureContext && location.hostname !== "localhost" && location.hostname !== "127.0.0.1") {
      setResult({ type: "error", text: "A leitura ao vivo precisa de HTTPS. Use o campo manual ou acesse o endereço seguro." });
      return;
    }

    try {
      setScanning(true);
      setResult(null);
      scannerRef.current = new Html5Qrcode(readerId);
      await scannerRef.current.start(
        { facingMode: "environment" },
        { fps: 10, qrbox: { width: 280, height: 120 }, formatsToSupport: [Html5QrcodeSupportedFormats.CODE_128] },
        async (decodedText) => {
          await stopScanner();
          await validate(decodedText);
        },
        () => {}
      );
    } catch (error) {
      setScanning(false);
      setResult({ type: "error", text: "Não foi possível abrir a câmera. Confira a permissão do navegador." });
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

  return h("section", { className: "scanner-box" },
    h("div", { className: "scanner-heading" },
      h("div", null, h("strong", null, "Conferência por código de barras"), h("span", null, "Code 128 ou código do produto")),
      h("span", { className: "scan-count" }, `${map.items.reduce((sum, item) => sum + (item.checkedQuantity || 0), 0)}/${map.items.reduce((sum, item) => sum + item.quantity, 0)}`)
    ),
    h("div", { id: readerId, className: `barcode-reader ${scanning ? "active" : ""}` }),
    h("div", { className: "scanner-controls" },
      h("input", {
        inputMode: "numeric",
        placeholder: "Digite o código ou use o leitor",
        value: manualCode,
        onChange: (event) => setManualCode(event.target.value),
        onKeyDown: (event) => event.key === "Enter" && validate(manualCode)
      }),
      h("button", {
        className: "secondary-action compact",
        onClick: scanning ? stopScanner : startScanner
      }, scanning ? "Fechar leitor" : "Escanear código")
    ),
    result && h("div", { className: `scan-result ${result.type}` }, result.text),
    h("div", { className: "scan-items" }, map.items.map((item) =>
      h("div", { className: (item.checkedQuantity || 0) >= item.quantity ? "complete" : "", key: item.sku },
        h("span", null, item.name),
        h("strong", null, `${item.checkedQuantity || 0}/${item.quantity}`)
      )
    ))
  );
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
  return new Date(value).toLocaleString("pt-BR");
}

async function extractCountsFromPdf(file) {
  if (!window.pdfjsLib) throw new Error("Leitor de PDF indisponível. Atualize a página e tente novamente.");
  window.pdfjsLib.GlobalWorkerOptions.workerSrc =
    "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";

  const pdf = await window.pdfjsLib.getDocument({ data: await file.arrayBuffer() }).promise;
  const lines = [];
  for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber++) {
    const page = await pdf.getPage(pageNumber);
    const content = await page.getTextContent();
    const grouped = new Map();

    content.items.forEach((item) => {
      const y = Math.round(item.transform[5] / 3) * 3;
      if (!grouped.has(y)) grouped.set(y, []);
      grouped.get(y).push({ x: item.transform[4], text: item.str.trim() });
    });

    [...grouped.entries()]
      .sort((a, b) => b[0] - a[0])
      .forEach(([, parts]) => {
        const line = parts.sort((a, b) => a.x - b.x).map((part) => part.text).filter(Boolean).join(" ");
        if (line) lines.push(line);
      });
  }

  const found = new Map();
  lines.forEach((line) => {
    const skuMatch = line.match(/\b(?:\d{4,}-\d+(?:\.\d+)?|\d{6,14})\b/);
    if (!skuMatch) return;
    const remainder = line.slice((skuMatch.index || 0) + skuMatch[0].length);
    const quantities = remainder.match(/-?\d+(?:[.,]\d+)?/g);
    if (!quantities || !quantities.length) return;
    const system = Math.max(0, Math.trunc(Number(quantities[0].replace(",", "."))));
    found.set(skuMatch[0], { sku: skuMatch[0], system, counted: 0 });
  });

  const rows = [...found.values()];
  if (!rows.length) {
    throw new Error("Não foi possível identificar linhas com SKU e saldo nesse PDF.");
  }
  return rows;
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
  return { maps: [], users: [], counts: [], errors: [], auditLog: [], metrics: {} };
}

ReactDOM.createRoot(document.querySelector("#root")).render(h(App));
