// Módulo de Inventário & Contagem 3.0 — MN-Check
// React 18 sem build step (React.createElement)
const { createElement: h, useState, useEffect, useRef, useMemo } = React;

const LOCATIONS = ["GERAL", "VENDAS", "DEPOSITO", "TROCAS", "OUTRO"];
const CONDITIONS = ["BOA", "AVARIA", "ASSISTENCIA", "OUTROS"];

function generateUUID() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function InventariosManager({
  branchCode: initialBranch = "281",
  request,
  user,
  token,
  latestImportId = null
}) {
  const [branchCode, setBranchCode] = useState(() => {
    try {
      return localStorage.getItem("mnCheckActiveBranch") || initialBranch || "281";
    } catch (_) {
      return initialBranch || "281";
    }
  });

  const [inventarios, setInventarios] = useState([]);
  const [activeView, setActiveView] = useState("list"); // 'list' | 'counting' | 'audit'
  const [currentInventory, setCurrentInventory] = useState(null);
  const [currentRoundId, setCurrentRoundId] = useState(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [showCreateModal, setShowCreateModal] = useState(false);

  useEffect(() => {
    try {
      localStorage.setItem("mnCheckActiveBranch", branchCode);
    } catch (_) {}
    loadInventarios();
  }, [branchCode]);

  async function loadInventarios() {
    setLoading(true);
    setError("");
    try {
      const res = await request(`/api/inventarios?branchCode=${encodeURIComponent(branchCode)}`);
      setInventarios(res.inventarios || []);
    } catch (err) {
      setError(err.message || "Erro ao carregar inventários.");
    } finally {
      setLoading(false);
    }
  }

  async function handleOpen(inv) {
    if (!confirm(`Deseja abrir o inventário "${inv.nome}"?`)) return;
    setError("");
    try {
      await request(`/api/inventarios/${inv.id}/abrir`, {
        method: "POST",
        body: { branchCode, expectedVersion: inv.version }
      });
      setFeedback("Inventário aberto com sucesso!");
      loadInventarios();
    } catch (err) {
      setError(err.message || "Não foi possível abrir o inventário.");
    }
  }

  async function handleStart(inv) {
    if (!confirm(`Deseja iniciar a Rodada 1 do inventário "${inv.nome}"?`)) return;
    setError("");
    try {
      const started = await request(`/api/inventarios/${inv.id}/iniciar`, {
        method: "POST",
        body: { branchCode, expectedVersion: inv.version }
      });
      setFeedback("Rodada 1 iniciada com sucesso!");
      loadInventarios();
      setCurrentInventory(started.inventory || inv);
      setActiveView("counting");
    } catch (err) {
      setError(err.message || "Não foi possível iniciar o inventário.");
    }
  }

  async function handleCancel(inv) {
    if (!confirm(`Tem certeza de que deseja cancelar o inventário "${inv.nome}"?`)) return;
    setError("");
    try {
      await request(`/api/inventarios/${inv.id}/cancelar`, {
        method: "POST",
        body: { branchCode, expectedVersion: inv.version }
      });
      setFeedback("Inventário cancelado.");
      loadInventarios();
    } catch (err) {
      setError(err.message || "Não foi possível cancelar o inventário.");
    }
  }

  function handleOpenCounting(inv) {
    setCurrentInventory(inv);
    setActiveView("counting");
  }

  function handleOpenAudit(inv, roundId = null) {
    setCurrentInventory(inv);
    setCurrentRoundId(roundId);
    setActiveView("audit");
  }

  if (activeView === "counting" && currentInventory) {
    return h(InventarioContagemScreen, {
      inventory: currentInventory,
      branchCode,
      request,
      user,
      onBack: () => {
        setActiveView("list");
        loadInventarios();
      },
      onRoundClosed: (closedRoundId) => {
        handleOpenAudit(currentInventory, closedRoundId);
      }
    });
  }

  if (activeView === "audit" && currentInventory) {
    return h(ApuracaoScreen, {
      inventory: currentInventory,
      roundId: currentRoundId,
      branchCode,
      request,
      user,
      onBack: () => {
        setActiveView("list");
        loadInventarios();
      },
      onStartRecount: () => {
        setActiveView("counting");
      }
    });
  }

  return h("div", { className: "inventarios-manager" },
    h("div", { className: "inventarios-header card-panel", style: { padding: "16px", marginBottom: "16px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
        h("div", null,
          h("h2", { style: { margin: "0 0 4px" } }, "Inventários Formais 3.0"),
          h("p", { className: "hint", style: { margin: 0 } }, "Sessões congeladas, rodadas auditáveis e recontagem cega.")
        ),
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center" } },
          h("button", {
            className: "btn btn-primary",
            onClick: () => setShowCreateModal(true),
            style: { fontWeight: "bold" }
          }, "+ Novo Inventário"),
          h("button", {
            className: "btn btn-secondary",
            onClick: loadInventarios,
            disabled: loading
          }, "↻ Atualizar")
        )
      )
    ),

    feedback && h("div", { className: "notice-banner notice-success", style: { margin: "12px 0" } },
      feedback,
      h("button", { className: "btn-link", onClick: () => setFeedback(""), style: { float: "right" } }, "✕")
    ),

    error && h("div", { className: "notice-banner notice-danger", style: { margin: "12px 0" } },
      error,
      h("button", { className: "btn-link", onClick: () => setError(""), style: { float: "right" } }, "✕")
    ),

    loading && h("div", { className: "card-panel", style: { textAlign: "center", padding: "30px" } },
      "Carregando inventários da filial ", branchCode, "..."
    ),

    !loading && inventarios.length === 0 && h("div", { className: "card-panel empty-state", style: { textAlign: "center", padding: "40px 20px" } },
      h("p", { style: { fontSize: "1.1rem", fontWeight: "600", color: "var(--text)" } }, "Nenhum inventário formal cadastrado nesta filial."),
      h("p", { className: "hint" }, "Clique em '+ Novo Inventário' para criar uma sessão congelada a partir do saldo importado."),
      h("button", { className: "btn btn-primary", onClick: () => setShowCreateModal(true), style: { marginTop: "12px" } },
        "Criar Primeiro Inventário"
      )
    ),

    !loading && inventarios.length > 0 && h("div", { className: "inventarios-grid", style: { display: "grid", gap: "12px", marginTop: "12px" } },
      inventarios.map((inv) => h("div", { key: inv.id, className: "card-panel inventory-card", style: { padding: "16px" } },
        h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "8px" } },
          h("div", null,
            h("div", { style: { display: "flex", gap: "6px", alignItems: "center", marginBottom: "4px" } },
              h("strong", { style: { fontSize: "1.1rem" } }, inv.nome),
              h("span", { className: `badge badge-${inv.modo === "CEGO" ? "warning" : "info"}` },
                inv.modo === "CEGO" ? "Contagem Cega" : "Normal"
              ),
              h("span", { className: "badge badge-neutral" }, inv.tipo)
            ),
            h("div", { className: "hint", style: { fontSize: "0.85rem" } },
              `Criado por ${inv.criadoPor} em ${new Date(inv.criadoEm).toLocaleString("pt-BR")} | ${inv.totalSkus} SKUs`
            )
          ),
          h("div", { style: { display: "flex", alignItems: "center", gap: "8px" } },
            h("span", { className: `badge badge-status badge-${inv.status.toLowerCase()}`, style: { fontWeight: "bold", padding: "6px 12px" } },
              inv.status
            )
          )
        ),
        h("div", { className: "inventory-card-actions", style: { display: "flex", gap: "8px", marginTop: "14px", flexWrap: "wrap" } },
          inv.status === "RASCUNHO" && h("button", {
            className: "btn btn-secondary",
            onClick: () => handleOpen(inv)
          }, "Abrir Sessão"),

          inv.status === "ABERTO" && h("button", {
            className: "btn btn-primary",
            onClick: () => handleStart(inv)
          }, "▶ Iniciar Rodada 1"),

          (inv.status === "EM_CONTAGEM" || inv.status === "EM_RECONTAGEM") && h("button", {
            className: "btn btn-primary",
            onClick: () => handleOpenCounting(inv),
            style: { background: "var(--accent-green, #10b981)", borderColor: "var(--accent-green, #10b981)" }
          }, inv.status === "EM_RECONTAGEM" ? "🔍 Recontagem (R2)" : "🔍 Abrir Contagem"),

          (inv.status === "EM_CONTAGEM" || inv.status === "EM_RECONTAGEM" || inv.status === "FINALIZADO" || inv.status === "EM_INVESTIGACAO") && h("button", {
            className: "btn btn-secondary",
            onClick: () => handleOpenAudit(inv),
            style: { fontWeight: "600" }
          }, "📊 Apuração"),

          (inv.status === "RASCUNHO" || inv.status === "ABERTO") && h("button", {
            className: "btn btn-danger-outline",
            onClick: () => handleCancel(inv)
          }, "Cancelar")
        )
      ))
    ),

    showCreateModal && h(CreateInventoryModal, {
      branchCode,
      latestImportId,
      request,
      onClose: () => setShowCreateModal(false),
      onCreated: () => {
        setShowCreateModal(false);
        setFeedback("Inventário criado com sucesso!");
        loadInventarios();
      }
    })
  );
}

function CreateInventoryModal({ branchCode, latestImportId, request, onClose, onCreated }) {
  const [nome, setNome] = useState("");
  const [tipo, setTipo] = useState("GERAL");
  const [modo, setModo] = useState("NORMAL");
  const [importId, setImportId] = useState(latestImportId ? String(latestImportId) : "");
  const [availableImports, setAvailableImports] = useState([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    async function loadImports() {
      try {
        const history = await request(`/api/historico/saldos?branchCode=${encodeURIComponent(branchCode)}&limit=10`);
        if (Array.isArray(history) && history.length > 0) {
          setAvailableImports(history);
          if (!importId) setImportId(String(history[0].id));
        }
      } catch (_) {}
    }
    loadImports();
  }, [branchCode]);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!nome.trim()) {
      setError("Informe o nome do inventário.");
      return;
    }
    const parsedImportId = parseInt(importId, 10);
    if (!parsedImportId || parsedImportId <= 0) {
      setError("Selecione uma importação de saldo válida.");
      return;
    }

    setSaving(true);
    setError("");
    try {
      await request("/api/inventarios", {
        method: "POST",
        body: {
          branchCode,
          importacaoSaldoId: parsedImportId,
          nome: nome.trim(),
          tipo,
          modo,
          skus: []
        }
      });
      onCreated();
    } catch (err) {
      setError(err.message || "Erro ao criar inventário.");
    } finally {
      setSaving(false);
    }
  }

  return h("div", { className: "modal-backdrop", onClick: onClose },
    h("div", { className: "modal-card", onClick: (e) => e.stopPropagation(), style: { maxWidth: "460px", width: "95%" } },
      h("h3", { style: { marginTop: 0 } }, "Novo Inventário Formal"),
      h("p", { className: "hint" }, `Filial ativa: ${branchCode}`),

      error && h("div", { className: "notice-banner notice-danger", style: { marginBottom: "12px" } }, error),

      h("form", { onSubmit: handleSubmit },
        h("div", { className: "form-group", style: { marginBottom: "12px" } },
          h("label", { style: { display: "block", marginBottom: "4px", fontWeight: "600" } }, "Nome do Inventário:"),
          h("input", {
            type: "text",
            className: "form-control",
            value: nome,
            onChange: (e) => setNome(e.target.value),
            placeholder: "Ex: Inventário Geral Setembro 2026",
            required: true,
            style: { width: "100%", padding: "8px", borderRadius: "6px" }
          })
        ),

        h("div", { className: "form-group", style: { marginBottom: "12px" } },
          h("label", { style: { display: "block", marginBottom: "4px", fontWeight: "600" } }, "Importação de Saldo Base:"),
          availableImports.length > 0
            ? h("select", {
                className: "form-control",
                value: importId,
                onChange: (e) => setImportId(e.target.value),
                style: { width: "100%", padding: "8px", borderRadius: "6px" }
              },
              availableImports.map((imp) => h("option", { key: imp.id, value: imp.id },
                `#${imp.id} - ${imp.fileName || "Importação"} (${imp.skuCount} SKUs)`
              ))
            )
            : h("input", {
                type: "number",
                className: "form-control",
                value: importId,
                onChange: (e) => setImportId(e.target.value),
                placeholder: "ID da Importação",
                required: true,
                style: { width: "100%", padding: "8px", borderRadius: "6px" }
              })
        ),

        h("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "10px", marginBottom: "12px" } },
          h("div", null,
            h("label", { style: { display: "block", marginBottom: "4px", fontWeight: "600" } }, "Tipo:"),
            h("select", {
              className: "form-control",
              value: tipo,
              onChange: (e) => setTipo(e.target.value),
              style: { width: "100%", padding: "8px", borderRadius: "6px" }
            },
              h("option", { value: "GERAL" }, "Geral (Todos SKUs)"),
              h("option", { value: "AUDITORIA" }, "Auditoria"),
              h("option", { value: "CICLICA" }, "Cíclica")
            )
          ),
          h("div", null,
            h("label", { style: { display: "block", marginBottom: "4px", fontWeight: "600" } }, "Modo de Contagem:"),
            h("select", {
              className: "form-control",
              value: modo,
              onChange: (e) => setModo(e.target.value),
              style: { width: "100%", padding: "8px", borderRadius: "6px" }
            },
              h("option", { value: "NORMAL" }, "Normal (Exibe Saldo)"),
              h("option", { value: "CEGO" }, "Cego (Oculta Saldo)")
            )
          )
        ),

        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "18px" } },
          h("button", { type: "button", className: "btn btn-secondary", onClick: onClose, disabled: saving }, "Cancelar"),
          h("button", { type: "submit", className: "btn btn-primary", disabled: saving },
            saving ? "Criando..." : "Criar Inventário"
          )
        )
      )
    )
  );
}

function InventarioContagemScreen({ inventory, branchCode, request, user, onBack, onRoundClosed }) {
  const [roundDetail, setRoundDetail] = useState(null);
  const [itemsData, setItemsData] = useState(null);
  const [search, setSearch] = useState("");
  const [estadoFilter, setEstadoFilter] = useState("all");
  const [selectedItem, setSelectedItem] = useState(null);

  // Localização ativa com persistência no localStorage
  const [activeLocation, setActiveLocation] = useState(() => {
    try {
      return localStorage.getItem("mnCheckActiveLocation") || "GERAL";
    } catch (_) {
      return "GERAL";
    }
  });

  const [stepperQuantity, setStepperQuantity] = useState(1);
  const [stepperCategory, setStepperCategory] = useState("BOA");
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState(null);
  const [error, setError] = useState("");

  // Modais de encerramento
  const [showCloseModal, setShowCloseModal] = useState(false);
  const [uncountedConflict, setUncountedConflict] = useState(null);
  const [closing, setClosing] = useState(false);

  const searchInputRef = useRef(null);

  useEffect(() => {
    loadActiveRoundAndItems();
  }, [inventory.id]);

  useEffect(() => {
    if (!selectedItem && searchInputRef.current) {
      searchInputRef.current.focus();
    }
  }, [selectedItem]);

  function handleSetLocation(loc) {
    setActiveLocation(loc);
    try {
      localStorage.setItem("mnCheckActiveLocation", loc);
    } catch (_) {}
  }

  async function loadActiveRoundAndItems(query = search, estado = estadoFilter) {
    setLoading(true);
    setError("");
    try {
      const roundRes = await request(`/api/inventarios/${inventory.id}/rodada-ativa?branchCode=${encodeURIComponent(branchCode)}`);
      setRoundDetail(roundRes);

      let itemsUrl = `/api/inventarios/${inventory.id}/itens?branchCode=${encodeURIComponent(branchCode)}`;
      if (query && query.trim()) itemsUrl += `&search=${encodeURIComponent(query.trim())}`;
      if (estado && estado !== "all") itemsUrl += `&estado=${encodeURIComponent(estado.toUpperCase())}`;

      const itemsRes = await request(itemsUrl);
      setItemsData(itemsRes);
    } catch (err) {
      setError(err.message || "Erro ao carregar dados da contagem.");
    } finally {
      setLoading(false);
    }
  }

  function handleSearchSubmit(e) {
    if (e) e.preventDefault();
    const cleanSearch = search.trim();
    if (!cleanSearch) {
      loadActiveRoundAndItems("", estadoFilter);
      return;
    }

    if (itemsData && itemsData.itens) {
      const match = itemsData.itens.find(i =>
        i.sku.toLowerCase() === cleanSearch.toLowerCase()
      );
      if (match) {
        openItemStepper(match);
        return;
      }
    }

    loadActiveRoundAndItems(cleanSearch, estadoFilter);
  }

  function openItemStepper(item) {
    setSelectedItem(item);
    const existingVal = item.quantidadeContada || 0;
    setStepperQuantity(existingVal > 0 ? existingVal : 1);
    setStepperCategory("BOA");
    setFeedback(null);
    setError("");
  }

  async function handleConfirmCount() {
    if (!selectedItem || !roundDetail) return;
    setSubmitting(true);
    setError("");
    setFeedback(null);

    const clientEventId = generateUUID();
    const isCorrection = (selectedItem.quantidadeContada || 0) > 0;
    const tipoAcao = isCorrection ? "CORRECAO" : "DEFINIR";

    try {
      const result = await request(`/api/inventarios/${inventory.id}/rodadas/${roundDetail.id}/contagens?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          sku: selectedItem.sku,
          quantidade: Math.max(0, parseInt(stepperQuantity, 10) || 0),
          localizacao: activeLocation,
          categoria: stepperCategory,
          tipoAcao,
          clientEventId,
          origem: "SCANNER",
          dispositivo: "PWA Mobile",
          clientTimestamp: new Date().toISOString(),
          referenciaId: selectedItem.ultimaOcorrenciaId || null
        }
      });

      setFeedback({
        type: "success",
        text: `Item ${selectedItem.sku} registrado com ${result.quantidadeItemProjetada} un em [${activeLocation} / ${stepperCategory}]!`
      });

      setSelectedItem(null);
      setSearch("");
      loadActiveRoundAndItems("", estadoFilter);
    } catch (err) {
      setError(err.message || "Falha ao gravar contagem.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCloseRound(force = false) {
    if (!roundDetail) return;
    setClosing(true);
    setError("");
    try {
      const audit = await request(`/api/inventarios/${inventory.id}/rodadas/${roundDetail.id}/encerrar?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: { forcar: force }
      });
      setShowCloseModal(false);
      setUncountedConflict(null);
      if (onRoundClosed) {
        onRoundClosed(roundDetail.id);
      }
    } catch (err) {
      if (err.pendentes != null && err.bloqueado) {
        setUncountedConflict(err);
      } else {
        setError(err.message || "Não foi possível encerrar a rodada.");
        setShowCloseModal(false);
      }
    } finally {
      setClosing(false);
    }
  }

  const isBlind = inventory.modo === "CEGO" || (roundDetail && roundDetail.numero >= 2);
  const isRecount = roundDetail && roundDetail.numero >= 2;
  const progress = itemsData?.progresso || roundDetail?.progresso;

  // Verificação de restrição de GERAL x Detalhada no item selecionado
  const itemHasGeral = selectedItem?.detalhes && Object.keys(selectedItem.detalhes).includes("GERAL");
  const itemHasDetailed = selectedItem?.detalhes && Object.keys(selectedItem.detalhes).some(k => k !== "GERAL");

  return h("div", { className: "inventario-contagem-view" },
    // Banner de Recontagem Cega (R2)
    isRecount && h("div", {
      className: "notice-banner notice-warning recount-blind-banner",
      style: {
        marginBottom: "12px",
        padding: "14px 18px",
        borderRadius: "10px",
        background: "rgba(245, 158, 11, 0.15)",
        border: "2px solid #f59e0b",
        color: "#d97706"
      }
    },
      h("div", { style: { display: "flex", alignItems: "center", gap: "10px" } },
        h("span", { style: { fontSize: "1.6rem" } }, "🔒"),
        h("div", null,
          h("strong", { style: { fontSize: "1.1rem", display: "block" } }, "RECONTAGEM — RODADA 2 (MODO CEGO OBRIGATÓRIO)"),
          h("span", { style: { fontSize: "0.85rem" } }, "Esta rodada contém apenas itens com divergências ou não contados na Rodada 1. O saldo esperado e contagens prévias estão estritamente ocultos para garantir auditoria imparcial.")
        )
      )
    ),

    // Barra superior
    h("div", { className: "card-panel contagem-header-card", style: { padding: "14px 16px", marginBottom: "12px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "8px" } },
        h("button", {
          className: "btn btn-secondary",
          onClick: onBack,
          style: { padding: "6px 12px", fontSize: "0.9rem" }
        }, "← Voltar aos Inventários"),

        h("div", { style: { display: "flex", gap: "6px", alignItems: "center" } },
          h("span", { className: `badge ${isRecount ? "badge-warning" : "badge-primary"}`, style: { fontSize: "0.9rem", fontWeight: "bold" } },
            roundDetail ? `Rodada ${roundDetail.numero} (${roundDetail.tipo})` : "Rodada Ativa"
          ),
          h("span", { className: `badge badge-${isBlind ? "warning" : "info"}`, style: { fontSize: "0.85rem" } },
            isBlind ? "MODO CEGO" : "NORMAL"
          ),
          h("button", {
            className: "btn btn-danger-outline",
            onClick: () => setShowCloseModal(true),
            style: { marginLeft: "6px", fontWeight: "bold", padding: "6px 12px", fontSize: "0.85rem" }
          }, `⏹ Encerrar Rodada ${roundDetail ? roundDetail.numero : ""}`)
        )
      ),

      h("div", { style: { marginTop: "10px" } },
        h("h3", { style: { margin: "0 0 4px", fontSize: "1.2rem" } }, inventory.nome),
        progress && h("div", { className: "progress-metrics-wrapper", style: { marginTop: "8px" } },
          h("div", { style: { display: "flex", justifyContent: "space-between", fontSize: "0.85rem", color: "var(--muted)", marginBottom: "4px" } },
            h("span", null, `Progresso: ${progress.contados} de ${progress.totalSkus} SKUs contados`),
            h("strong", { style: { color: "var(--text)" } }, `${progress.percentual}%`)
          ),
          h("div", { className: "progress-bar-container", style: { width: "100%", height: "8px", background: "var(--border, #333)", borderRadius: "4px", overflow: "hidden" } },
            h("div", {
              style: {
                width: `${progress.percentual}%`,
                height: "100%",
                background: isRecount ? "#f59e0b" : "var(--accent-green, #10b981)",
                transition: "width 0.3s ease"
              }
            })
          )
        )
      )
    ),

    feedback && h("div", { className: "notice-banner notice-success", style: { margin: "8px 0" } },
      feedback.text
    ),

    error && h("div", { className: "notice-banner notice-danger", style: { margin: "8px 0" } },
      error
    ),

    // Modal de Contagem Touch (Item Selecionado)
    selectedItem && h("div", { className: "stepper-modal-overlay card-panel", style: { border: "2px solid var(--primary, #3b82f6)", marginBottom: "16px", padding: "16px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "flex-start" } },
        h("div", null,
          h("span", { className: "badge badge-neutral" }, "Item Selecionado"),
          h("h2", { style: { margin: "4px 0", fontSize: "1.6rem", fontWeight: "bold" } }, selectedItem.sku),
          h("p", { className: "hint", style: { margin: "0 0 6px", fontSize: "0.95rem" } }, selectedItem.descricao)
        ),
        h("button", {
          className: "btn btn-secondary",
          onClick: () => setSelectedItem(null),
          style: { padding: "4px 10px" }
        }, "✕ Fechar")
      ),

      h("div", { className: "item-info-row", style: { display: "flex", gap: "10px", margin: "10px 0", flexWrap: "wrap" } },
        !isBlind && h("div", { className: "metric-tag" },
          h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Saldo Esperado:"),
          h("strong", { style: { fontSize: "1.1rem" } }, selectedItem.saldoSnapshot ?? "0")
        ),
        isBlind && h("div", { className: "metric-tag", style: { background: "rgba(245, 158, 11, 0.15)", color: "#f59e0b", padding: "6px 10px", borderRadius: "6px" } },
          h("span", { style: { fontSize: "0.8rem", fontWeight: "bold" } }, "🔒 Modo Cego (Saldo Oculto)")
        ),
        h("div", { className: "metric-tag" },
          h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Contagem Atual nesta Rodada:"),
          h("strong", { style: { fontSize: "1.1rem", color: "var(--accent-green, #10b981)" } },
            selectedItem.quantidadeContada ? `${selectedItem.quantidadeContada} un` : "Pendente"
          )
        )
      ),

      // Seletor Rápido de Localização
      h("div", { style: { margin: "12px 0 8px" } },
        h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Localização Física:"),
        h("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap" } },
          LOCATIONS.map((loc) => {
            const isGeral = loc === "GERAL";
            const isDisabled = (isGeral && itemHasDetailed) || (!isGeral && itemHasGeral);
            return h("button", {
              key: loc,
              type: "button",
              disabled: isDisabled,
              className: `btn ${activeLocation === loc ? "btn-primary" : "btn-secondary"}`,
              onClick: () => handleSetLocation(loc),
              style: { padding: "6px 12px", fontSize: "0.85rem", opacity: isDisabled ? 0.4 : 1 }
            }, loc);
          })
        ),
        itemHasGeral && h("span", { className: "hint", style: { fontSize: "0.75rem", color: "#f59e0b", display: "block", marginTop: "2px" } },
          "Item já possui registro em GERAL. Localizações detalhadas bloqueadas para este item."
        ),
        itemHasDetailed && h("span", { className: "hint", style: { fontSize: "0.75rem", color: "#f59e0b", display: "block", marginTop: "2px" } },
          "Item possui registro em local detalhado. GERAL bloqueado para este item."
        )
      ),

      // Seletor Rápido de Condição
      h("div", { style: { margin: "12px 0 8px" } },
        h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Condição do Produto:"),
        h("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap" } },
          CONDITIONS.map((cat) => h("button", {
            key: cat,
            type: "button",
            className: `btn ${stepperCategory === cat ? "btn-primary" : "btn-secondary"}`,
            onClick: () => setStepperCategory(cat),
            style: { padding: "6px 12px", fontSize: "0.85rem" }
          }, cat))
        )
      ),

      // Stepper Touch Grande
      h("div", { className: "stepper-touch-container", style: { marginTop: "14px" } },
        h("div", { style: { display: "flex", alignItems: "center", justifyContent: "center", gap: "12px" } },
          h("button", {
            type: "button",
            className: "btn stepper-btn",
            onClick: () => setStepperQuantity(q => Math.max(0, (parseInt(q, 10) || 0) - 1)),
            style: { width: "64px", height: "64px", fontSize: "2rem", borderRadius: "12px", fontWeight: "bold" }
          }, "−"),

          h("input", {
            type: "number",
            className: "form-control stepper-input",
            value: stepperQuantity,
            onChange: (e) => setStepperQuantity(e.target.value),
            min: "0",
            style: { width: "120px", height: "64px", fontSize: "2rem", textAlign: "center", fontWeight: "bold", borderRadius: "12px" }
          }),

          h("button", {
            type: "button",
            className: "btn stepper-btn",
            onClick: () => setStepperQuantity(q => (parseInt(q, 10) || 0) + 1),
            style: { width: "64px", height: "64px", fontSize: "2rem", borderRadius: "12px", fontWeight: "bold" }
          }, "+")
        ),

        // Botões de incremento rápido
        h("div", { style: { display: "flex", justifyContent: "center", gap: "8px", marginTop: "12px" } },
          [1, 5, 10, 50].map((inc) => h("button", {
            key: inc,
            type: "button",
            className: "btn btn-secondary",
            onClick: () => setStepperQuantity(q => (parseInt(q, 10) || 0) + inc),
            style: { padding: "8px 14px", fontWeight: "600" }
          }, `+${inc}`)),
          h("button", {
            type: "button",
            className: "btn btn-secondary",
            onClick: () => setStepperQuantity(0),
            style: { padding: "8px 14px", color: "var(--danger, #ef4444)" }
          }, "Zerar")
        )
      ),

      // Botão de Confirmação Touch Grande
      h("button", {
        type: "button",
        className: "btn btn-primary",
        onClick: handleConfirmCount,
        disabled: submitting,
        style: {
          width: "100%",
          height: "56px",
          fontSize: "1.2rem",
          fontWeight: "bold",
          marginTop: "18px",
          borderRadius: "10px",
          background: "var(--accent-green, #10b981)",
          borderColor: "var(--accent-green, #10b981)"
        }
      }, submitting ? "Gravando..." : `✔ Confirmar [${activeLocation} / ${stepperCategory}]`)
    ),

    // Busca e Scanner
    h("div", { className: "card-panel search-scanner-panel", style: { padding: "12px", marginBottom: "12px" } },
      h("form", { onSubmit: handleSearchSubmit, style: { display: "flex", gap: "8px" } },
        h("input", {
          ref: searchInputRef,
          type: "text",
          className: "form-control",
          value: search,
          onChange: (e) => setSearch(e.target.value),
          placeholder: "Escanear código de barras ou digitar SKU...",
          style: { flex: 1, padding: "10px 14px", fontSize: "1rem", borderRadius: "8px" }
        }),
        h("button", {
          type: "submit",
          className: "btn btn-primary",
          style: { padding: "10px 16px", fontWeight: "bold" }
        }, "🔍 Buscar")
      ),

      // Filtros de Estado
      h("div", { style: { display: "flex", gap: "8px", marginTop: "10px" } },
        [
          { id: "all", label: "Todos" },
          { id: "pendente", label: "Pendentes" },
          { id: "contado", label: "Contados" }
        ].map(f => h("button", {
          key: f.id,
          type: "button",
          className: `btn ${estadoFilter === f.id ? "btn-primary" : "btn-secondary"}`,
          onClick: () => {
            setEstadoFilter(f.id);
            loadActiveRoundAndItems(search, f.id);
          },
          style: { padding: "4px 12px", fontSize: "0.85rem", borderRadius: "20px" }
        }, f.label))
      )
    ),

    // Lista de Itens da Rodada
    h("div", { className: "inventory-items-list" },
      loading && h("div", { className: "card-panel", style: { textAlign: "center", padding: "20px" } },
        "Carregando itens..."
      ),

      !loading && itemsData && itemsData.itens.length === 0 && h("div", { className: "card-panel", style: { textAlign: "center", padding: "30px" } },
        h("p", { className: "hint" }, "Nenhum item encontrado com o filtro atual.")
      ),

      !loading && itemsData && itemsData.itens.map(item => h("div", {
        key: item.id,
        className: `card-panel item-row-card ${item.estado === "CONTADO" ? "item-counted" : ""}`,
        onClick: () => openItemStepper(item),
        style: {
          padding: "12px 16px",
          marginBottom: "8px",
          cursor: "pointer",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderLeft: item.estado === "CONTADO" ? "4px solid var(--accent-green, #10b981)" : "4px solid var(--border)"
        }
      },
        h("div", null,
          h("div", { style: { display: "flex", alignItems: "center", gap: "8px" } },
            h("strong", { style: { fontSize: "1.05rem" } }, item.sku),
            h("span", { className: `badge ${item.estado === "CONTADO" ? "badge-success" : "badge-neutral"}` },
              item.estado
            )
          ),
          h("div", { className: "hint", style: { fontSize: "0.85rem", marginTop: "2px" } }, item.descricao)
        ),

        h("div", { style: { textAlign: "right" } },
          item.quantidadeContada > 0 && h("div", { style: { fontWeight: "bold", fontSize: "1.1rem", color: "var(--accent-green, #10b981)" } },
            `${item.quantidadeContada} un`
          ),
          !isBlind && item.saldoSnapshot != null && h("div", { className: "hint", style: { fontSize: "0.8rem" } },
            `Esp: ${item.saldoSnapshot}`
          ),
          h("span", { style: { fontSize: "0.85rem", color: "var(--primary, #3b82f6)" } }, "Toque para contar →")
        )
      ))
    ),

    // Modal de Confirmação de Encerramento
    showCloseModal && h("div", { className: "modal-backdrop", onClick: () => setShowCloseModal(false) },
      h("div", { className: "modal-card", onClick: e => e.stopPropagation(), style: { maxWidth: "460px", width: "95%" } },
        h("h3", { style: { marginTop: 0, color: "var(--danger, #ef4444)" } }, `Encerrar Rodada ${roundDetail ? roundDetail.numero : ""}?`),
        h("p", null, "Esta operação é ", h("strong", null, "irreversível"), ". Após o encerramento, nenhuma nova contagem ou correção poderá ser registrada nesta rodada."),

        uncountedConflict && h("div", { className: "notice-banner notice-danger", style: { marginBottom: "14px", textAlign: "left" } },
          h("strong", { style: { display: "block", marginBottom: "4px" } }, "Atenção: Itens Não Contados!"),
          `Existem ${uncountedConflict.pendentes} itens pendentes que não foram bipados ou contados. O encerramento normal foi bloqueado.`,
          h("p", { style: { fontSize: "0.85rem", marginTop: "6px" } }, "Para prosseguir, utilize o Encerramento Forçado. O sistema registrará seu usuário na auditoria e preservará estes itens com o estado 'NÃO CONTADO'.")
        ),

        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "20px" } },
          h("button", {
            type: "button",
            className: "btn btn-secondary",
            onClick: () => {
              setShowCloseModal(false);
              setUncountedConflict(null);
            },
            disabled: closing
          }, "Voltar"),

          uncountedConflict
            ? h("button", {
                type: "button",
                className: "btn btn-danger",
                onClick: () => handleCloseRound(true),
                disabled: closing
              }, closing ? "Forçando..." : "⚠ Confirmar Encerramento Forçado")
            : h("button", {
                type: "button",
                className: "btn btn-danger",
                onClick: () => handleCloseRound(false),
                disabled: closing
              }, closing ? "Encerrando..." : "Confirmar Encerramento")
        )
      )
    )
  );
}

function ApuracaoScreen({ inventory, roundId, branchCode, request, user, onBack, onStartRecount }) {
  const [audit, setAudit] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [activeTab, setActiveTab] = useState("divergentes"); // 'divergentes' | 'todos' | 'nao_contados' | 'conformes'
  const [selectedItemIds, setSelectedItemIds] = useState(new Set());
  const [startingRecount, setStartingRecount] = useState(false);

  useEffect(() => {
    loadAudit();
  }, [roundId, inventory.id]);

  async function loadAudit() {
    setLoading(true);
    setError("");
    try {
      let url = `/api/inventarios/${inventory.id}`;
      // Se roundId foi informado, busca a apuração específica. Se não, busca a rodada ativa ou rodada 1
      const activeRound = await request(`/api/inventarios/${inventory.id}/rodada-ativa?branchCode=${encodeURIComponent(branchCode)}`).catch(() => null);
      const targetRoundId = roundId || (activeRound ? activeRound.id : 1);

      const res = await request(`/api/inventarios/${inventory.id}/rodadas/${targetRoundId}/apuracao?branchCode=${encodeURIComponent(branchCode)}`);
      setAudit(res);

      // Pré-selecionar divergentes para R2 se for Rodada 1
      if (res.rodadaNumero === 1) {
        const divIds = new Set(
          res.itens.filter(i => i.estado === "DIVERGENTE").map(i => i.inventarioItemId)
        );
        setSelectedItemIds(divIds);
      }
    } catch (err) {
      setError(err.message || "Erro ao carregar apuração da rodada.");
    } finally {
      setLoading(false);
    }
  }

  function toggleItemSelection(itemId, estado) {
    if (estado === "CONFORME") return;
    setSelectedItemIds(prev => {
      const next = new Set(prev);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  }

  function selectAllDivergentes() {
    if (!audit) return;
    const divIds = new Set(audit.itens.filter(i => i.estado === "DIVERGENTE").map(i => i.inventarioItemId));
    setSelectedItemIds(divIds);
  }

  function includeNaoContados() {
    if (!audit) return;
    setSelectedItemIds(prev => {
      const next = new Set(prev);
      audit.itens.filter(i => i.estado === "NAO_CONTADO").forEach(i => next.add(i.inventarioItemId));
      return next;
    });
  }

  function clearSelection() {
    setSelectedItemIds(new Set());
  }

  async function handleCreateRecount() {
    if (selectedItemIds.size === 0) {
      alert("Selecione pelo menos um item para a recontagem.");
      return;
    }
    if (!confirm(`Deseja iniciar a Rodada 2 (Recontagem Cega) com ${selectedItemIds.size} itens selecionados?`)) return;

    setStartingRecount(true);
    setError("");
    try {
      await request(`/api/inventarios/${inventory.id}/criar-recontagem?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: { itemIds: Array.from(selectedItemIds) }
      });
      if (onStartRecount) {
        onStartRecount();
      }
    } catch (err) {
      setError(err.message || "Não foi possível criar a Rodada 2 de recontagem.");
      setStartingRecount(false);
    }
  }

  if (loading) {
    return h("div", { className: "card-panel", style: { textAlign: "center", padding: "40px" } },
      "Carregando dados da apuração..."
    );
  }

  if (!audit) {
    return h("div", { className: "card-panel", style: { padding: "20px", textAlign: "center" } },
      error && h("div", { className: "notice-banner notice-danger" }, error),
      h("button", { className: "btn btn-secondary", onClick: onBack, style: { marginTop: "12px" } }, "← Voltar")
    );
  }

  const { resumo, itens, rodadaNumero, rodadaTipo, encerramentoForcado, pendentesNoFechamento } = audit;
  const isR2 = rodadaNumero >= 2;

  const filteredItems = itens.filter(item => {
    if (activeTab === "divergentes") return item.estado === "DIVERGENTE" || item.estado === "DIVERGENCIA_CONFIRMADA";
    if (activeTab === "nao_contados") return item.estado === "NAO_CONTADO";
    if (activeTab === "conformes") return item.estado === "CONFORME" || item.estado === "CONFORME_APOS_RECONTAGEM";
    return true;
  });

  return h("div", { className: "apuracao-screen-view" },
    // Top Bar
    h("div", { className: "card-panel apuracao-header", style: { padding: "14px 18px", marginBottom: "14px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
        h("button", {
          className: "btn btn-secondary",
          onClick: onBack,
          style: { padding: "6px 12px", fontSize: "0.9rem" }
        }, "← Voltar aos Inventários"),

        h("div", { style: { display: "flex", gap: "8px", alignItems: "center" } },
          h("span", { className: `badge ${isR2 ? "badge-warning" : "badge-primary"}`, style: { fontSize: "0.9rem", fontWeight: "bold" } },
            `Apuração da Rodada ${rodadaNumero} (${rodadaTipo})`
          ),
          encerramentoForcado && h("span", { className: "badge badge-danger", style: { fontSize: "0.85rem" } },
            `Encerramento Forçado (${pendentesNoFechamento} pendentes)`
          )
        )
      ),

      h("div", { style: { marginTop: "10px" } },
        h("h3", { style: { margin: "0 0 2px" } }, inventory.nome),
        h("p", { className: "hint", style: { margin: 0, fontSize: "0.85rem" } },
          `Apurado por ${audit.finalizadaPor || "Supervisor"} em ${audit.finalizadaEm ? new Date(audit.finalizadaEm).toLocaleString("pt-BR") : "agora"}`
        )
      )
    ),

    error && h("div", { className: "notice-banner notice-danger", style: { margin: "10px 0" } }, error),

    // Cards de Métricas Consolidadas
    h("div", { className: "audit-card-metrics", style: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(130px, 1fr))", gap: "10px", marginBottom: "14px" } },
      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Total SKUs"),
        h("strong", { style: { fontSize: "1.4rem" } }, resumo.totalItens)
      ),

      !isR2 && h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #10b981" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Conformes"),
        h("strong", { style: { fontSize: "1.4rem", color: "#10b981" } }, resumo.conformes)
      ),

      !isR2 && h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #ef4444" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Divergentes"),
        h("strong", { style: { fontSize: "1.4rem", color: "#ef4444" } }, resumo.divergentes)
      ),

      isR2 && h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #10b981" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Conforme pós-R2"),
        h("strong", { style: { fontSize: "1.4rem", color: "#10b981" } }, resumo.conformesAposRecontagem)
      ),

      isR2 && h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #ef4444" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Divergência Confirmada"),
        h("strong", { style: { fontSize: "1.4rem", color: "#ef4444" } }, resumo.divergenciasConfirmadas)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #6b7280" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Não Contados"),
        h("strong", { style: { fontSize: "1.4rem", color: "#6b7280" } }, resumo.naoContados)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Falta / Sobra"),
        h("span", { style: { fontSize: "1.1rem", fontWeight: "bold" } },
          h("span", { style: { color: "#ef4444" } }, `-${resumo.totalFalta}`),
          " / ",
          h("span", { style: { color: "#10b981" } }, `+${resumo.totalSobra}`)
        )
      )
    ),

    // Ações de Seleção de Recontagem (somente na Rodada 1)
    !isR2 && h("div", { className: "card-panel", style: { padding: "12px", marginBottom: "12px", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "8px" } },
      h("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap" } },
        h("button", { className: "btn btn-secondary", onClick: selectAllDivergentes, style: { fontSize: "0.85rem" } },
          `Selecionar Divergentes (${resumo.divergentes})`
        ),
        h("button", { className: "btn btn-secondary", onClick: includeNaoContados, style: { fontSize: "0.85rem" } },
          `Incluir Não Contados (${resumo.naoContados})`
        ),
        h("button", { className: "btn btn-secondary", onClick: clearSelection, style: { fontSize: "0.85rem" } },
          "Limpar Seleção"
        )
      ),
      h("button", {
        className: "btn btn-primary",
        onClick: handleCreateRecount,
        disabled: startingRecount || selectedItemIds.size === 0,
        style: { fontWeight: "bold", background: "#f59e0b", borderColor: "#f59e0b" }
      }, startingRecount ? "Iniciando R2..." : `🚀 Iniciar Recontagem (R2) com ${selectedItemIds.size} itens`)
    ),

    // Filtros de Abas
    h("div", { style: { display: "flex", gap: "8px", marginBottom: "12px", flexWrap: "wrap" } },
      [
        { id: "divergentes", label: isR2 ? "Divergências Confirmadas" : "Divergentes" },
        { id: "todos", label: "Todos os Itens" },
        { id: "nao_contados", label: "Não Contados" },
        { id: "conformes", label: isR2 ? "Conformes pós-R2" : "Conformes" }
      ].map(tab => h("button", {
        key: tab.id,
        type: "button",
        className: `btn ${activeTab === tab.id ? "btn-primary" : "btn-secondary"}`,
        onClick: () => setActiveTab(tab.id),
        style: { borderRadius: "20px", fontSize: "0.85rem", padding: "6px 14px" }
      }, tab.label))
    ),

    // Tabela de Apuração
    h("div", { className: "card-panel", style: { padding: 0, overflowX: "auto" } },
      h("table", { className: "table", style: { width: "100%", borderCollapse: "collapse" } },
        h("thead", null,
          h("tr", { style: { borderBottom: "1px solid var(--border)", background: "rgba(0,0,0,0.02)" } },
            !isR2 && h("th", { style: { padding: "10px", width: "40px", textAlign: "center" } }, "R2"),
            h("th", { style: { padding: "10px", textAlign: "left" } }, "SKU"),
            h("th", { style: { padding: "10px", textAlign: "left" } }, "Descrição"),
            h("th", { style: { padding: "10px", textAlign: "right" } }, "Saldo Esp."),
            h("th", { style: { padding: "10px", textAlign: "right" } }, "Físico Contado"),
            h("th", { style: { padding: "10px", textAlign: "right" } }, "Diferença"),
            h("th", { style: { padding: "10px", textAlign: "center" } }, "Estado"),
            h("th", { style: { padding: "10px", textAlign: "left" } }, "Localização / Condição")
          )
        ),
        h("tbody", null,
          filteredItems.length === 0 && h("tr", null,
            h("td", { colSpan: 8, style: { textAlign: "center", padding: "30px" }, className: "hint" },
              "Nenhum item encontrado nesta categoria."
            )
          ),
          filteredItems.map(item => {
            const isSelected = selectedItemIds.has(item.inventarioItemId);
            const isConforme = item.estado === "CONFORME" || item.estado === "CONFORME_APOS_RECONTAGEM";
            const isDivergente = item.estado === "DIVERGENTE" || item.estado === "DIVERGENCIA_CONFIRMADA";

            return h("tr", {
              key: item.inventarioItemId,
              style: {
                borderBottom: "1px solid var(--border)",
                background: isSelected ? "rgba(245, 158, 11, 0.06)" : "transparent"
              }
            },
              !isR2 && h("td", { style: { padding: "10px", textAlign: "center" } },
                h("input", {
                  type: "checkbox",
                  checked: isSelected,
                  disabled: isConforme,
                  onChange: () => toggleItemSelection(item.inventarioItemId, item.estado)
                })
              ),
              h("td", { style: { padding: "10px", fontWeight: "bold" } }, item.sku),
              h("td", { style: { padding: "10px", fontSize: "0.85rem" } }, item.descricao),
              h("td", { style: { padding: "10px", textAlign: "right" } }, item.saldoSnapshot),
              h("td", { style: { padding: "10px", textAlign: "right", fontWeight: "bold" } },
                item.quantidadeFisica != null ? `${item.quantidadeFisica} un` : "—"
              ),
              h("td", { style: { padding: "10px", textAlign: "right", fontWeight: "bold" } },
                item.diferenca != null
                  ? h("span", { style: { color: item.diferenca === 0 ? "#10b981" : item.diferenca < 0 ? "#ef4444" : "#f59e0b" } },
                      item.diferenca > 0 ? `+${item.diferenca}` : item.diferenca
                    )
                  : "—"
              ),
              h("td", { style: { padding: "10px", textAlign: "center" } },
                h("span", {
                  className: `badge ${isConforme ? "badge-success" : isDivergente ? "badge-danger" : "badge-neutral"}`,
                  style: { fontSize: "0.75rem", fontWeight: "bold" }
                }, item.estado)
              ),
              h("td", { style: { padding: "10px", fontSize: "0.8rem", color: "var(--muted)" } },
                item.detalhes && Object.keys(item.detalhes).length > 0
                  ? Object.entries(item.detalhes).map(([loc, cats]) =>
                      `${loc}: [${Object.entries(cats).filter(([_, q]) => q > 0).map(([c, q]) => `${c}: ${q}`).join(", ")}]`
                    ).join(" | ")
                  : "—"
              )
            );
          })
        )
      )
    )
  );
}

if (typeof window !== "undefined") {
  window.MNCheckInventarios = {
    InventariosManager,
    InventarioContagemScreen,
    ApuracaoScreen,
    CreateInventoryModal
  };
}
