// Módulo de Inventário & Contagem 3.0 — MN-Check
// React 18 sem build step (React.createElement)
const { createElement: h, useState, useEffect, useRef, useMemo } = React;

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
  const [selectedInventory, setSelectedInventory] = useState(null);
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
      setSelectedInventory(started.inventory || inv);
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

  if (selectedInventory) {
    return h(InventarioContagemScreen, {
      inventory: selectedInventory,
      branchCode,
      request,
      user,
      onBack: () => {
        setSelectedInventory(null);
        loadInventarios();
      }
    });
  }

  return h("div", { className: "inventarios-container" },
    h("div", { className: "inventarios-header card-panel" },
      h("div", { className: "inventarios-title-row" },
        h("div", null,
          h("h2", { style: { margin: 0, fontSize: "1.35rem", display: "flex", alignItems: "center", gap: "8px" }, "data-testid": "title-inventarios" },
            "Sessões de Inventário 3.0"
          ),
          h("p", { className: "hint", style: { margin: "4px 0 0" } },
            "Ciclo auditável por rodadas de contagem imutáveis."
          )
        ),
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center" } },
          h("label", { style: { fontSize: "0.85rem", color: "var(--muted)" } }, "Filial:"),
          h("input", {
            type: "text",
            value: branchCode,
            onChange: (e) => setBranchCode(e.target.value.trim()),
            style: { width: "70px", padding: "6px 8px", borderRadius: "6px", textAlign: "center", fontWeight: "bold" },
            placeholder: "281"
          }),
          h("button", {
            className: "btn btn-primary",
            onClick: () => setShowCreateModal(true),
            style: { padding: "8px 14px" }
          }, "+ Novo Inventário")
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

          inv.status === "EM_CONTAGEM" && h("button", {
            className: "btn btn-primary",
            onClick: () => setSelectedInventory(inv),
            style: { background: "var(--accent-green, #10b981)", borderColor: "var(--accent-green, #10b981)" }
          }, "🔍 Abrir Contagem"),

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

function InventarioContagemScreen({ inventory, branchCode, request, user, onBack }) {
  const [roundDetail, setRoundDetail] = useState(null);
  const [itemsData, setItemsData] = useState(null);
  const [search, setSearch] = useState("");
  const [estadoFilter, setEstadoFilter] = useState("all");
  const [selectedItem, setSelectedItem] = useState(null);
  const [stepperQuantity, setStepperQuantity] = useState(1);
  const [stepperCategory, setStepperCategory] = useState("BOA");
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState(null);
  const [error, setError] = useState("");

  const searchInputRef = useRef(null);

  useEffect(() => {
    loadActiveRoundAndItems();
  }, [inventory.id]);

  useEffect(() => {
    if (!selectedItem && searchInputRef.current) {
      searchInputRef.current.focus();
    }
  }, [selectedItem]);

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
        text: `Item ${selectedItem.sku} registrado com ${result.quantidadeItemProjetada} unidades!`
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

  const isBlind = inventory.modo === "CEGO";
  const progress = itemsData?.progresso || roundDetail?.progresso;

  return h("div", { className: "inventario-contagem-view" },
    // Barra superior
    h("div", { className: "card-panel contagem-header-card", style: { padding: "12px 16px", marginBottom: "12px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center" } },
        h("button", {
          className: "btn btn-secondary",
          onClick: onBack,
          style: { padding: "6px 12px", fontSize: "0.9rem" }
        }, "← Voltar aos Inventários"),
        h("div", { style: { display: "flex", gap: "6px", alignItems: "center" } },
          h("span", { className: "badge badge-primary", style: { fontSize: "0.85rem" } },
            roundDetail ? `Rodada ${roundDetail.numero} (${roundDetail.tipo})` : "Rodada Ativa"
          ),
          h("span", { className: `badge badge-${isBlind ? "warning" : "info"}`, style: { fontSize: "0.85rem" } },
            isBlind ? "CEGO" : "NORMAL"
          )
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
                background: "var(--accent-green, #10b981)",
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
        }, "Fechar")
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
          h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Última Leitura:"),
          h("strong", { style: { fontSize: "1.1rem", color: "var(--accent-green, #10b981)" } },
            selectedItem.quantidadeContada ? `${selectedItem.quantidadeContada} un` : "Pendente"
          )
        )
      ),

      // Seletor de Categoria
      h("div", { style: { margin: "12px 0 8px" } },
        h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Categoria do Produto:"),
        h("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap" } },
          ["BOA", "AVARIA", "ASSISTENCIA", "OUTROS"].map((cat) => h("button", {
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
      }, submitting ? "Gravando..." : "✔ Confirmar Contagem")
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

    // Lista de Itens do Inventário
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
    )
  );
}

if (typeof window !== "undefined") {
  window.MNCheckInventarios = {
    InventariosManager,
    InventarioContagemScreen,
    CreateInventoryModal
  };
}
