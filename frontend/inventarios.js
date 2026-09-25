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
  const [activeView, setActiveView] = useState("list"); // 'list' | 'counting' | 'audit' | 'investigations' | 'closing' | 'history' | 'report'
  const [statusFilter, setStatusFilter] = useState("TODOS"); // 'TODOS' | 'EM_ANDAMENTO' | 'ENCERRADOS' | 'ABERTOS'
  const [currentInventory, setCurrentInventory] = useState(null);
  const [currentRoundId, setCurrentRoundId] = useState(null);
  const [currentInvestigationId, setCurrentInvestigationId] = useState(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [showCreateModal, setShowCreateModal] = useState(false);

  useEffect(() => {
    try {
      localStorage.setItem("mnCheckActiveBranch", branchCode);
    } catch (_) {}
    loadInventarios(statusFilter);
  }, [branchCode, statusFilter]);

  async function loadInventarios(filter = statusFilter) {
    setLoading(true);
    setError("");
    try {
      const queryParam = filter && filter !== "TODOS" ? `&status=${encodeURIComponent(filter)}` : "";
      const res = await request(`/api/inventarios?branchCode=${encodeURIComponent(branchCode)}${queryParam}`);
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

  function handleOpenInvestigations(inv, investigationId = null) {
    setCurrentInventory(inv);
    setCurrentInvestigationId(investigationId);
    setActiveView("investigations");
  }

  function handleOpenClosing(inv) {
    setCurrentInventory(inv);
    setActiveView("closing");
  }

  function handleOpenHistory(inv) {
    setCurrentInventory(inv);
    setActiveView("history");
  }

  function handleOpenReport(inv) {
    setCurrentInventory(inv);
    setActiveView("report");
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
      },
      onOpenInvestigations: (inv, invId) => {
        handleOpenInvestigations(inv, invId);
      },
      onOpenClosing: (inv) => {
        handleOpenClosing(inv || currentInventory);
      }
    });
  }

  if (activeView === "investigations" && currentInventory) {
    return h(InvestigacoesScreen, {
      inventory: currentInventory,
      branchCode,
      request,
      user,
      initialInvestigationId: currentInvestigationId,
      onBack: () => {
        setActiveView("audit");
      },
      onBackToList: () => {
        setActiveView("list");
        loadInventarios();
      },
      onOpenClosing: (inv) => {
        handleOpenClosing(inv || currentInventory);
      }
    });
  }

  if (activeView === "closing" && currentInventory) {
    return h(FechamentoScreen, {
      inventory: currentInventory,
      branchCode,
      request,
      user,
      onBack: () => {
        setActiveView("list");
        loadInventarios();
      },
      onGoToAudit: () => {
        handleOpenAudit(currentInventory);
      },
      onGoToInvestigations: () => {
        handleOpenInvestigations(currentInventory);
      },
      onClosed: () => {
        setActiveView("history");
        loadInventarios();
      }
    });
  }

  if (activeView === "history" && currentInventory) {
    return h(HistoricoResultadoScreen, {
      inventory: currentInventory,
      branchCode,
      request,
      user,
      onBack: () => {
        setActiveView("list");
        loadInventarios();
      }
    });
  }

  if (activeView === "report" && currentInventory) {
    return h(InventoryReportScreen, {
      inventory: currentInventory,
      branchCode,
      request,
      token,
      onBack: () => {
        setActiveView("list");
        loadInventarios();
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

    h("div", { className: "filter-tabs", style: { display: "flex", gap: "8px", margin: "14px 0", flexWrap: "wrap" } },
      [
        { id: "TODOS", label: "Todos os Inventários" },
        { id: "EM_ANDAMENTO", label: "Em Andamento" },
        { id: "ENCERRADOS", label: "Encerrados / Histórico" },
        { id: "ABERTOS", label: "Abertos / Rascunhos" }
      ].map(tab => h("button", {
        key: tab.id,
        className: `btn ${statusFilter === tab.id ? "btn-primary" : "btn-secondary"}`,
        onClick: () => setStatusFilter(tab.id),
        style: { borderRadius: "20px", fontSize: "0.85rem", padding: "6px 14px" }
      }, tab.label))
    ),

    loading && h("div", { className: "card-panel", style: { textAlign: "center", padding: "30px" } },
      "Carregando inventários da filial ", branchCode, "..."
    ),

    !loading && inventarios.length === 0 && h("div", { className: "card-panel empty-state", style: { textAlign: "center", padding: "40px 20px" } },
      h("p", { style: { fontSize: "1.1rem", fontWeight: "600", color: "var(--text)" } }, "Nenhum inventário formal encontrado nesta filial para o filtro selecionado."),
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
          inv.status === "ENCERRADO" && h("button", {
            className: "btn btn-primary",
            onClick: () => handleOpenHistory(inv),
            style: { background: "#4f46e5", borderColor: "#4f46e5", fontWeight: "bold" }
          }, "🏆 Ver Resultado / Histórico"),

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

          (inv.status === "EM_CONTAGEM" || inv.status === "EM_RECONTAGEM" || inv.status === "FINALIZADO" || inv.status === "EM_INVESTIGACAO" || inv.status === "ENCERRADO") && h("button", {
            className: "btn btn-secondary",
            onClick: () => handleOpenAudit(inv),
            style: { fontWeight: "600" }
          }, "📊 Apuração"),

          (inv.status === "EM_CONTAGEM" || inv.status === "EM_RECONTAGEM" || inv.status === "FINALIZADO" || inv.status === "EM_INVESTIGACAO" || inv.status === "ENCERRADO") && h("button", {
            className: "btn btn-secondary",
            onClick: () => handleOpenInvestigations(inv),
            style: { fontWeight: "600" }
          }, "🔎 Investigações"),

          (inv.status === "EM_CONTAGEM" || inv.status === "EM_RECONTAGEM" || inv.status === "FINALIZADO" || inv.status === "EM_INVESTIGACAO" || inv.status === "ENCERRADO") && h("button", {
            className: "btn btn-secondary",
            onClick: () => handleOpenReport(inv),
            style: { fontWeight: "600" }
          }, "🖨 Relatório / Exportar"),

          (inv.status === "EM_CONTAGEM" || inv.status === "EM_RECONTAGEM" || inv.status === "FINALIZADO" || inv.status === "EM_INVESTIGACAO") && h("button", {
            className: "btn btn-secondary",
            onClick: () => handleOpenClosing(inv),
            style: { fontWeight: "bold" }
          }, "🏁 Fechamento"),

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

function InventoryReportScreen({ inventory, branchCode, request, token, onBack }) {
  const [filters, setFilters] = useState({
    contexto: inventory.status === "ENCERRADO" ? "RESULTADO_FINAL" : (inventory.status === "EM_RECONTAGEM" ? "R2" : "R1"),
    status: "TODOS", localizacao: "TODAS", condicao: "TODAS", investigacao: "TODAS", busca: "", ordenarPor: "SKU"
  });
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  function queryString() {
    const params = new URLSearchParams({ branchCode, ...filters });
    return params.toString();
  }

  async function loadReport() {
    setLoading(true); setError("");
    try { setReport(await request(`/api/inventarios/${inventory.id}/relatorio?${queryString()}`)); }
    catch (err) { setReport(null); setError(err.message || "Não foi possível gerar o relatório."); }
    finally { setLoading(false); }
  }

  useEffect(() => { loadReport(); }, [filters.contexto, filters.status, filters.localizacao, filters.condicao, filters.investigacao, filters.ordenarPor]);

  async function exportXlsx() {
    setError("");
    try {
      const response = await fetch(`/api/inventarios/${inventory.id}/relatorio.xlsx?${queryString()}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
      if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.error || "Falha ao exportar XLSX."); }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob); const anchor = document.createElement("a");
      anchor.href = url; anchor.download = `inventario-${inventory.id}-${filters.contexto.toLowerCase()}.xlsx`; anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) { setError(err.message || "Falha ao exportar XLSX."); }
  }

  const update = (key) => (event) => setFilters(current => ({ ...current, [key]: event.target.value }));
  const filterLabel = report ? `Status ${report.filtros.status} · Localização ${report.filtros.localizacao} · Condição ${report.filtros.condicao} · Investigação ${report.filtros.investigacao}` : "";

  return h("div", { className: "inventory-report-view" },
    h("div", { className: "card-panel inventory-report-controls" },
      h("div", { className: "inventory-report-toolbar" },
        h("button", { className: "btn btn-secondary", onClick: onBack }, "← Voltar"),
        h("h2", null, "Relatório do Inventory 3.0"),
        h("div", { className: "inventory-report-actions" },
          h("button", { className: "btn btn-secondary", onClick: loadReport, disabled: loading }, "Atualizar"),
          h("button", { className: "btn btn-secondary", onClick: () => window.print(), disabled: !report }, "🖨 Imprimir / PDF"),
          h("button", { className: "btn btn-primary", onClick: exportXlsx, disabled: !report }, "⬇ Exportar XLSX")
        )
      ),
      h("div", { className: "inventory-report-filters" },
        reportSelect("Contexto", filters.contexto, update("contexto"), [["R1","Rodada 1"],["R2","Rodada 2"],["RESULTADO_FINAL","Resultado final"]]),
        reportSelect("Status", filters.status, update("status"), [["TODOS","Todos"],["CONTADOS","Contados"],["NAO_CONTADOS","Não contados"],["CONFORMES","Conformes"],["DIVERGENTES","Divergentes"]]),
        reportSelect("Localização", filters.localizacao, update("localizacao"), ["TODAS","GERAL","VENDAS","DEPOSITO","TROCAS","OUTRO"].map(v => [v,v])),
        reportSelect("Condição", filters.condicao, update("condicao"), ["TODAS","BOA","AVARIA","ASSISTENCIA","OUTROS"].map(v => [v,v])),
        reportSelect("Investigação", filters.investigacao, update("investigacao"), ["TODAS","SEM_INVESTIGACAO","PENDENTE","EM_INVESTIGACAO","AGUARDANDO_EVIDENCIA","RESOLVIDA","SEM_CAUSA_IDENTIFICADA"].map(v => [v,v.replaceAll("_"," ")])),
        reportSelect("Ordenar", filters.ordenarPor, update("ordenarPor"), ["SKU","DESCRICAO","LOCALIZACAO","STATUS","DIFERENCA"].map(v => [v,v.replaceAll("_"," ")])),
        h("label", null, h("span", null, "SKU ou descrição"), h("div", { style: { display: "flex", gap: "6px" } },
          h("input", { type: "search", value: filters.busca, onChange: update("busca"), onKeyDown: e => { if (e.key === "Enter") loadReport(); }, placeholder: "Pesquisar..." }),
          h("button", { className: "btn btn-secondary", onClick: loadReport }, "Buscar")
        ))
      )
    ),
    error && h("div", { className: "notice-banner notice-danger" }, error),
    loading && h("div", { className: "card-panel", style: { padding: "30px", textAlign: "center" } }, "Gerando projeção somente leitura..."),
    !loading && report && h("section", { className: "inventory-report-sheet" },
      h("header", { className: "inventory-report-header" },
        h("div", null, h("span", null, "MN-Check"), h("h1", null, "Relatório de Inventário 3.0"), h("p", null, `${report.inventarioNome} · Sessão #${report.inventarioId}`)),
        h("strong", null, `Filial ${report.filial}`)
      ),
      h("div", { className: "inventory-report-meta" },
        h("span", null, `Contexto: ${report.contexto}`), h("span", null, `Emitido: ${new Date(report.emitidoEm).toLocaleString("pt-BR")}`), h("span", null, filterLabel)
      ),
      report.camposProtegidos && h("div", { className: "notice-banner notice-warning" }, "🔒 Modo cego ativo: saldo, diferença, resultado esperado e investigação estão protegidos pelo servidor."),
      h("div", { className: "inventory-report-summary" },
        metric("Itens", report.resumo.total), metric("Contados", report.resumo.contados), metric("Não contados", report.resumo.naoContados), metric("Conformes", report.resumo.conformes), metric("Divergentes", report.resumo.divergentes), metric("Quantidade", report.resumo.quantidadeTotal)
      ),
      report.itens.length === 0 ? h("div", { className: "empty-state" }, "Nenhum item corresponde aos filtros selecionados.") :
      h("div", { className: "inventory-report-table-wrap" }, h("table", { className: "inventory-report-table" },
        h("thead", null, h("tr", null, ["SKU","Descrição","Localização","Condição","Quantidade","Estado", ...(report.camposProtegidos ? [] : ["Saldo","Diferença"]), "Investigação","Conclusão"].map(label => h("th", { key: label }, label)))),
        h("tbody", null, report.itens.map((item, index) => h("tr", { key: `${item.sku}-${index}` },
          h("td", null, item.sku), h("td", null, item.descricao), h("td", null, reportItemLocations(item)), h("td", null, reportItemConditions(item)),
          h("td", { className: "numeric" }, item.quantidade ?? "—"), h("td", null, item.estado),
          !report.camposProtegidos && h("td", { className: "numeric" }, item.saldo ?? "—"), !report.camposProtegidos && h("td", { className: "numeric" }, item.diferenca ?? "—"),
          h("td", null, item.statusInvestigacao || "—"), h("td", null, item.conclusao || "—")
        )))
      )),
      h("footer", { className: "inventory-report-signatures" }, h("div", null, "Responsável pela contagem"), h("div", null, "Responsável pela validação"))
    )
  );
}

function reportSelect(label, value, onChange, options) {
  return h("label", null, h("span", null, label), h("select", { value, onChange }, options.map(([id, text]) => h("option", { key: id, value: id }, text))));
}
function metric(label, value) { return h("div", null, h("span", null, label), h("strong", null, value)); }
function reportItemLocations(item) { return Object.keys(item.detalhes || {}).join(", ") || "—"; }
function reportItemConditions(item) { const values = new Set(); Object.values(item.detalhes || {}).forEach(group => Object.keys(group || {}).forEach(key => values.add(key))); return [...values].join(", ") || "—"; }

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
  const [selectedOrigin, setSelectedOrigin] = useState("MANUAL");

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
  const scanTimingRef = useRef({ first: 0, last: 0, keys: 0 });

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
    const timing = scanTimingRef.current;
    const elapsed = timing.last - timing.first;
    const detectedOrigin = cleanSearch.length >= 4 && timing.keys >= cleanSearch.length && elapsed > 0
      && elapsed / Math.max(1, timing.keys - 1) <= 55 ? "SCANNER" : "MANUAL";
    if (!cleanSearch) {
      loadActiveRoundAndItems("", estadoFilter);
      return;
    }

    if (itemsData && itemsData.itens) {
      const match = itemsData.itens.find(i =>
        i.sku.toLowerCase() === cleanSearch.toLowerCase()
      );
      if (match) {
        openItemStepper(match, detectedOrigin);
        return;
      }
    }

    loadActiveRoundAndItems(cleanSearch, estadoFilter);
  }

  function openItemStepper(item, origin = "MANUAL") {
    setSelectedItem(item);
    setSelectedOrigin(origin);
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
          origem: selectedOrigin,
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
          onChange: (e) => {
            const now = Date.now();
            const timing = scanTimingRef.current;
            if (!timing.last || now - timing.last > 180 || e.target.value.length <= 1) {
              scanTimingRef.current = { first: now, last: now, keys: e.target.value.length };
            } else {
              scanTimingRef.current = { first: timing.first, last: now, keys: timing.keys + 1 };
            }
            setSearch(e.target.value);
          },
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

function ApuracaoScreen({ inventory, roundId, branchCode, request, user, onBack, onStartRecount, onOpenInvestigations, onOpenClosing }) {
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

  async function handleInvestigateItem(item) {
    if (!onOpenInvestigations) return;
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          apuracaoId: item.apuracaoId,
          causaSuspeita: null,
          justificativa: `Abertura de investigação para divergência do SKU ${item.sku}`
        }
      });
      onOpenInvestigations(inventory, res.id);
    } catch (err) {
      onOpenInvestigations(inventory);
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
          h("button", {
            className: "btn btn-secondary",
            onClick: () => onOpenInvestigations ? onOpenInvestigations(inventory) : null,
            style: { padding: "6px 12px", fontSize: "0.9rem" }
          }, "🔎 Investigações"),
          h("button", {
            className: "btn btn-secondary",
            onClick: () => onOpenClosing ? onOpenClosing(inventory) : null,
            style: { padding: "6px 12px", fontSize: "0.9rem", fontWeight: "600" }
          }, "🏁 Fechamento"),
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
            h("th", { style: { padding: "10px", textAlign: "left" } }, "Localização / Condição"),
            h("th", { style: { padding: "10px", textAlign: "center" } }, "Ação")
          )
        ),
        h("tbody", null,
          filteredItems.length === 0 && h("tr", null,
            h("td", { colSpan: 9, style: { textAlign: "center", padding: "30px" }, className: "hint" },
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
              ),
              h("td", { style: { padding: "10px", textAlign: "center" } },
                (isDivergente || item.estado === "NAO_CONTADO") && h("button", {
                  className: "btn btn-secondary",
                  style: { fontSize: "0.75rem", padding: "4px 8px", whiteSpace: "nowrap" },
                  onClick: () => handleInvestigateItem(item)
                }, "🔎 Investigar")
              )
            );
          })
        )
      )
    )
  );
}

const INVESTIGATION_STATUSES = [
  { id: "TODAS", label: "Todas" },
  { id: "PENDENTE", label: "Pendente" },
  { id: "EM_INVESTIGACAO", label: "Em Investigação" },
  { id: "AGUARDANDO_EVIDENCIA", label: "Aguardando Evidência" },
  { id: "RESOLVIDA", label: "Resolvida" },
  { id: "SEM_CAUSA_IDENTIFICADA", label: "Sem Causa" }
];

const CAUSES_MAP = {
  ERRO_CONTAGEM: "Erro de Contagem",
  INVERSAO_PRODUTO: "Inversão de Produto",
  INVERSAO_VOLTAGEM: "Inversão de Voltagem (110V/220V)",
  AVARIA: "Avaria não Registrada",
  ASSISTENCIA: "Produto em Assistência Técnica",
  ERRO_SEPARACAO: "Erro de Separação / Expedição",
  MERCADORIA_CLIENTE: "Mercadoria de Cliente",
  MOVIMENTACAO: "Movimentação Interna Pendente",
  FISCAL_NF: "Divergência Fiscal / NF",
  NAO_LOCALIZADO: "Produto Não Localizado",
  OUTRO: "Outro Motivo",
  NAO_IDENTIFICADA: "Causa Não Identificada"
};

const EVIDENCE_TYPES = [
  { id: "OBSERVACAO", label: "Observação Operacional" },
  { id: "FOTO", label: "Foto / Registro Visual" },
  { id: "DOCUMENTO", label: "Documento Interno" },
  { id: "NOTA_FISCAL", label: "Nota Fiscal / XML" },
  { id: "CONTAGEM", label: "Recontagem Pontual" },
  { id: "PRODUTO_RELACIONADO", label: "Verificação de Produto Relacionado" },
  { id: "OUTRO", label: "Outra Evidência" }
];

const LINK_TYPES = [
  { id: "POSSIVEL_INVERSAO", label: "Possível Inversão de Produto" },
  { id: "POSSIVEL_VOLTAGEM", label: "Possível Inversão de Voltagem" },
  { id: "MESMO_PRODUTO", label: "Mesmo Produto (Outro Código/Local)" },
  { id: "MOVIMENTACAO_RELACIONADA", label: "Movimentação Relacionada" },
  { id: "OUTRO", label: "Outro Vínculo" }
];

function InvestigacoesScreen({
  inventory,
  branchCode,
  request,
  user,
  initialInvestigationId = null,
  onBack,
  onBackToList,
  onOpenClosing
}) {
  const [investigations, setInvestigations] = useState([]);
  const [selectedId, setSelectedId] = useState(initialInvestigationId);
  const [detail, setDetail] = useState(null);
  const [statusFilter, setStatusFilter] = useState("TODAS");
  const [searchQuery, setSearchQuery] = useState("");
  const [loadingList, setLoadingList] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");

  // Modals & Inline Form states
  const [showStartModal, setShowStartModal] = useState(false);
  const [startForm, setStartForm] = useState({ responsavelNome: user?.name || "Supervisor", causaSuspeita: "", justificativa: "" });

  const [showSuspectModal, setShowSuspectModal] = useState(false);
  const [suspectForm, setSuspectForm] = useState({ causaSuspeita: "", justificativa: "" });

  const [showResolveModal, setShowResolveModal] = useState(false);
  const [resolveForm, setResolveForm] = useState({ causaConfirmada: "ERRO_CONTAGEM", conclusao: "" });

  const [showUnresolvedModal, setShowUnresolvedModal] = useState(false);
  const [unresolvedForm, setUnresolvedForm] = useState({ conclusao: "" });

  const [showReopenModal, setShowReopenModal] = useState(false);
  const [reopenForm, setReopenForm] = useState({ justificativa: "" });

  const [newEvidence, setNewEvidence] = useState({ tipo: "OBSERVACAO", descricao: "", referencia: "" });
  const [addingEvidence, setAddingEvidence] = useState(false);

  const [newLink, setNewLink] = useState({ inventarioItemRelacionadoId: "", tipoVinculo: "POSSIVEL_INVERSAO", observacao: "" });
  const [addingLink, setAddingLink] = useState(false);

  useEffect(() => {
    loadList();
  }, [inventory.id, branchCode]);

  useEffect(() => {
    if (selectedId) {
      loadDetail(selectedId);
    } else {
      setDetail(null);
    }
  }, [selectedId]);

  async function loadList(preferredId = null) {
    setLoadingList(true);
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes?branchCode=${encodeURIComponent(branchCode)}`);
      const list = res || [];
      setInvestigations(list);
      const targetId = preferredId || selectedId || (list.length > 0 ? list[0].id : null);
      if (targetId) {
        setSelectedId(targetId);
      }
    } catch (err) {
      setError(err.message || "Erro ao carregar investigações.");
    } finally {
      setLoadingList(false);
    }
  }

  async function loadDetail(id) {
    if (!id) return;
    setLoadingDetail(true);
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${id}?branchCode=${encodeURIComponent(branchCode)}`);
      setDetail(res);
      // Preencher formulários com estado atual
      setSuspectForm({ causaSuspeita: res.causaSuspeita || "", justificativa: "" });
    } catch (err) {
      setError(err.message || "Erro ao carregar detalhes da investigação.");
    } finally {
      setLoadingDetail(false);
    }
  }

  async function handleStartInvestigation() {
    if (!selectedId) return;
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/iniciar?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          responsavelId: user?.name || "Supervisor",
          responsavelNome: startForm.responsavelNome || user?.name || "Supervisor",
          causaSuspeita: startForm.causaSuspeita || null,
          justificativa: startForm.justificativa || null
        }
      });
      setFeedback("Investigação iniciada com sucesso!");
      setShowStartModal(false);
      setDetail(res);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao iniciar investigação.");
    }
  }

  async function handleUpdateSuspectCause() {
    if (!selectedId || !suspectForm.causaSuspeita) return;
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/suspeita?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          causaSuspeita: suspectForm.causaSuspeita,
          justificativa: suspectForm.justificativa || null
        }
      });
      setFeedback("Causa suspeita atualizada!");
      setShowSuspectModal(false);
      setDetail(res);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao atualizar causa suspeita.");
    }
  }

  async function handleChangeStatus(targetStatus, motivo = "") {
    if (!selectedId) return;
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/status?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: { status: targetStatus, motivo: motivo || null }
      });
      setFeedback(`Status alterado para ${targetStatus}!`);
      setDetail(res);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao alterar status.");
    }
  }

  async function handleAddEvidence(e) {
    if (e) e.preventDefault();
    if (!selectedId || !newEvidence.descricao.trim()) return;
    setAddingEvidence(true);
    setError("");
    try {
      await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/evidencias?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          tipo: newEvidence.tipo,
          descricao: newEvidence.descricao.trim(),
          referencia: newEvidence.referencia.trim() || null
        }
      });
      setFeedback("Evidência adicionada com sucesso!");
      setNewEvidence({ tipo: "OBSERVACAO", descricao: "", referencia: "" });
      loadDetail(selectedId);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao adicionar evidência.");
    } finally {
      setAddingEvidence(false);
    }
  }

  async function handleAddLink(e) {
    if (e) e.preventDefault();
    if (!selectedId || !newLink.inventarioItemRelacionadoId) {
      alert("Selecione o produto relacionado para criar o vínculo.");
      return;
    }
    setAddingLink(true);
    setError("");
    try {
      await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/vinculos?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          inventarioItemRelacionadoId: Number(newLink.inventarioItemRelacionadoId),
          apuracaoRelacionadaId: null,
          tipoVinculo: newLink.tipoVinculo,
          observacao: newLink.observacao.trim() || null
        }
      });
      setFeedback("Vínculo registrado com sucesso!");
      setNewLink({ inventarioItemRelacionadoId: "", tipoVinculo: "POSSIVEL_INVERSAO", observacao: "" });
      loadDetail(selectedId);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao vincular produto.");
    } finally {
      setAddingLink(false);
    }
  }

  async function handleResolveInvestigation() {
    if (!selectedId) return;
    if (!resolveForm.causaConfirmada || resolveForm.causaConfirmada === "NAO_IDENTIFICADA") {
      alert("Selecione uma causa confirmada válida para resolver.");
      return;
    }
    if (!resolveForm.conclusao || resolveForm.conclusao.trim().length < 5) {
      alert("Informe uma conclusão detalhada com no mínimo 5 caracteres.");
      return;
    }
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/resolver?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          causaConfirmada: resolveForm.causaConfirmada,
          conclusao: resolveForm.conclusao.trim()
        }
      });
      setFeedback("Divergência resolvida com sucesso!");
      setShowResolveModal(false);
      setDetail(res);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao resolver divergência.");
    }
  }

  async function handleCloseUnresolved() {
    if (!selectedId) return;
    if (!unresolvedForm.conclusao || unresolvedForm.conclusao.trim().length < 10) {
      alert("Informe uma justificativa com no mínimo 10 caracteres explicando por que a causa não foi identificada.");
      return;
    }
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/sem-causa?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          conclusao: unresolvedForm.conclusao.trim()
        }
      });
      setFeedback("Investigação encerrada como sem causa identificada.");
      setShowUnresolvedModal(false);
      setDetail(res);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao encerrar investigação.");
    }
  }

  async function handleReopenInvestigation() {
    if (!selectedId) return;
    if (!reopenForm.justificativa || reopenForm.justificativa.trim().length < 10) {
      alert("Informe a justificativa da reabertura com no mínimo 10 caracteres.");
      return;
    }
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/investigacoes/${selectedId}/reabrir?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          justificativa: reopenForm.justificativa.trim()
        }
      });
      setFeedback("Investigação reaberta com sucesso!");
      setShowReopenModal(false);
      setDetail(res);
      loadList(selectedId);
    } catch (err) {
      setError(err.message || "Erro ao reabrir investigação.");
    }
  }

  // Filtragem da lista
  const filteredList = investigations.filter(inv => {
    if (statusFilter !== "TODAS" && inv.status !== statusFilter) return false;
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      const matchSku = inv.sku && inv.sku.toLowerCase().includes(q);
      const matchDesc = inv.descricaoSnapshot && inv.descricaoSnapshot.toLowerCase().includes(q);
      return matchSku || matchDesc;
    }
    return true;
  });

  // Contadores para os filtros
  const counts = useMemo(() => {
    const c = { TODAS: investigations.length, PENDENTE: 0, EM_INVESTIGACAO: 0, AGUARDANDO_EVIDENCIA: 0, RESOLVIDA: 0, SEM_CAUSA_IDENTIFICADA: 0 };
    investigations.forEach(inv => {
      if (c[inv.status] !== undefined) c[inv.status]++;
    });
    return c;
  }, [investigations]);

  const isFinalized = detail && (detail.status === "RESOLVIDA" || detail.status === "SEM_CAUSA_IDENTIFICADA");

  return h("div", { className: "investigacoes-screen-view" },
    // Header
    h("div", { className: "card-panel", style: { padding: "14px 18px", marginBottom: "14px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" } },
          h("button", { className: "btn btn-secondary", onClick: onBack, style: { fontSize: "0.85rem", padding: "6px 12px" } },
            "← Apuração"
          ),
          h("button", { className: "btn btn-secondary", onClick: onBackToList, style: { fontSize: "0.85rem", padding: "6px 12px" } },
            "📋 Lista de Inventários"
          ),
          h("button", { className: "btn btn-secondary", onClick: () => onOpenClosing ? onOpenClosing(inventory) : null, style: { fontSize: "0.85rem", padding: "6px 12px", fontWeight: "600" } },
            "🏁 Fechamento"
          ),
          h("h3", { style: { margin: 0 } }, "Investigação de Divergências"),
          h("span", { className: "badge badge-neutral" }, inventory.nome),
          h("span", { className: "badge badge-primary" }, `Filial ${branchCode}`)
        ),
        h("button", { className: "btn btn-secondary", onClick: () => loadList(selectedId), style: { fontSize: "0.85rem" } },
          "↻ Atualizar"
        )
      )
    ),

    feedback && h("div", { className: "notice-banner notice-success", style: { margin: "10px 0" } },
      feedback,
      h("button", { className: "btn-link", onClick: () => setFeedback(""), style: { float: "right" } }, "✕")
    ),

    error && h("div", { className: "notice-banner notice-danger", style: { margin: "10px 0" } },
      error,
      h("button", { className: "btn-link", onClick: () => setError(""), style: { float: "right" } }, "✕")
    ),

    // Filtros de Status (Cards/Abas)
    h("div", { style: { display: "flex", gap: "8px", marginBottom: "14px", flexWrap: "wrap" } },
      INVESTIGATION_STATUSES.map(st => h("button", {
        key: st.id,
        type: "button",
        className: `btn ${statusFilter === st.id ? "btn-primary" : "btn-secondary"}`,
        onClick: () => setStatusFilter(st.id),
        style: { borderRadius: "20px", fontSize: "0.85rem", padding: "6px 14px", display: "flex", alignItems: "center", gap: "6px" }
      },
        st.label,
        h("span", {
          style: {
            background: statusFilter === st.id ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.08)",
            padding: "1px 6px",
            borderRadius: "10px",
            fontSize: "0.75rem",
            fontWeight: "bold"
          }
        }, counts[st.id] || 0)
      ))
    ),

    // Layout Dividido: Lista à Esquerda (340px) e Detalhe à Direita
    h("div", { style: { display: "grid", gridTemplateColumns: "minmax(280px, 340px) 1fr", gap: "16px", alignItems: "start" } },

      // Coluna da Esquerda: Lista de Divergências
      h("div", { className: "card-panel", style: { padding: "12px", maxHeight: "80vh", overflowY: "auto" } },
        h("div", { style: { marginBottom: "10px" } },
          h("input", {
            type: "text",
            placeholder: "🔍 Buscar SKU ou descrição...",
            value: searchQuery,
            onChange: e => setSearchQuery(e.target.value),
            style: { width: "100%", padding: "8px 10px", borderRadius: "6px", border: "1px solid var(--border)", fontSize: "0.85rem", boxSizing: "border-box" }
          })
        ),

        loadingList && h("div", { style: { textAlign: "center", padding: "20px" }, className: "hint" }, "Carregando..."),

        !loadingList && filteredList.length === 0 && h("div", { style: { textAlign: "center", padding: "30px 10px" }, className: "hint" },
          investigations.length === 0
            ? "Nenhuma divergência registrada para investigação."
            : "Nenhuma investigação encontrada para os filtros atuais."
        ),

        !loadingList && filteredList.map(inv => {
          const isSelected = selectedId === inv.id;
          const diff = inv.diferenca;
          const isResolved = inv.status === "RESOLVIDA";
          const isSemCausa = inv.status === "SEM_CAUSA_IDENTIFICADA";

          return h("div", {
            key: inv.id,
            onClick: () => setSelectedId(inv.id),
            style: {
              padding: "10px",
              borderRadius: "8px",
              marginBottom: "8px",
              cursor: "pointer",
              border: isSelected ? "2px solid #2563eb" : "1px solid var(--border)",
              background: isSelected ? "rgba(37, 99, 235, 0.05)" : "transparent",
              transition: "all 0.15s ease"
            }
          },
            h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: "4px" } },
              h("strong", { style: { fontSize: "0.95rem" } }, inv.sku),
              diff != null && h("span", {
                style: {
                  fontWeight: "bold",
                  fontSize: "0.85rem",
                  color: diff === 0 ? "#10b981" : diff < 0 ? "#ef4444" : "#f59e0b"
                }
              }, diff > 0 ? `+${diff}` : diff)
            ),
            h("div", { style: { fontSize: "0.8rem", color: "var(--muted)", marginBottom: "6px", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" } },
              inv.descricaoSnapshot || "Sem descrição"
            ),
            h("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "center" } },
              h("span", {
                className: `badge ${isResolved ? "badge-success" : isSemCausa ? "badge-neutral" : "badge-warning"}`,
                style: { fontSize: "0.7rem", padding: "2px 6px" }
              }, inv.status),
              inv.causaConfirmada && h("span", {
                className: "badge badge-info",
                style: { fontSize: "0.7rem", padding: "2px 6px" }
              }, CAUSES_MAP[inv.causaConfirmada] || inv.causaConfirmada),
              !inv.causaConfirmada && inv.causaSuspeita && h("span", {
                className: "badge badge-neutral",
                style: { fontSize: "0.7rem", padding: "2px 6px" }
              }, `Suspeita: ${CAUSES_MAP[inv.causaSuspeita] || inv.causaSuspeita}`)
            )
          );
        })
      ),

      // Coluna da Direita: Detalhe Completo da Investigação
      h("div", { className: "card-panel", style: { padding: "18px" } },
        !selectedId && h("div", { style: { textAlign: "center", padding: "50px" }, className: "hint" },
          "Selecione uma investigação na lista ao lado para acompanhar o histórico, evidências e desfecho."
        ),

        loadingDetail && h("div", { style: { textAlign: "center", padding: "40px" } }, "Carregando detalhes da investigação..."),

        !loadingDetail && detail && h("div", null,
          // Cabeçalho do Detalhe
          h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "10px", borderBottom: "1px solid var(--border)", paddingBottom: "12px", marginBottom: "14px" } },
            h("div", null,
              h("div", { style: { display: "flex", alignItems: "center", gap: "8px", marginBottom: "4px" } },
                h("h2", { style: { margin: 0, fontSize: "1.3rem" } }, detail.sku),
                h("span", {
                  className: `badge ${detail.status === "RESOLVIDA" ? "badge-success" : detail.status === "SEM_CAUSA_IDENTIFICADA" ? "badge-neutral" : "badge-warning"}`,
                  style: { fontSize: "0.85rem", fontWeight: "bold" }
                }, detail.status)
              ),
              h("p", { style: { margin: 0, fontSize: "0.9rem", color: "var(--muted)" } }, detail.descricaoSnapshot || "Sem descrição cadastrada")
            ),
            h("div", { style: { textAlign: "right" } },
              h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } },
                `Aberta em ${new Date(detail.criadoEm).toLocaleString("pt-BR")} por ${detail.criadoPor}`
              ),
              detail.responsavelNome && h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } },
                `Responsável: <strong>${detail.responsavelNome}</strong>`
              )
            )
          ),

          // Seção 1: Origem da Divergência & Dados Físicos
          h("div", { style: { background: "rgba(0,0,0,0.02)", padding: "12px 14px", borderRadius: "8px", marginBottom: "16px" } },
            h("h4", { style: { margin: "0 0 8px 0", fontSize: "0.95rem" } }, "📊 Origem da Divergência (Apuração)"),
            h("div", { style: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(110px, 1fr))", gap: "10px", textAlign: "center" } },
              h("div", null,
                h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Saldo Esperado"),
                h("strong", { style: { fontSize: "1.1rem" } }, detail.apuracao ? detail.apuracao.saldoSnapshot : "—")
              ),
              h("div", null,
                h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Físico Contado"),
                h("strong", { style: { fontSize: "1.1rem" } }, detail.apuracao && detail.apuracao.quantidadeFisica != null ? `${detail.apuracao.quantidadeFisica} un` : "—")
              ),
              h("div", null,
                h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Diferença"),
                h("strong", {
                  style: {
                    fontSize: "1.1rem",
                    color: detail.apuracao && detail.apuracao.diferenca < 0 ? "#ef4444" : "#f59e0b"
                  }
                }, detail.apuracao && detail.apuracao.diferenca != null ? (detail.apuracao.diferenca > 0 ? `+${detail.apuracao.diferenca}` : detail.apuracao.diferenca) : "—")
              ),
              h("div", null,
                h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Rodada de Origem"),
                h("span", { className: "badge badge-neutral", style: { fontSize: "0.75rem" } },
                  detail.apuracao ? `R${detail.apuracao.rodadaNumero} (${detail.apuracao.rodadaTipo})` : "—"
                )
              )
            ),
            detail.apuracao && detail.apuracao.detalhesLocalizacaoCondicao && Object.keys(detail.apuracao.detalhesLocalizacaoCondicao).length > 0 && h("div", { style: { marginTop: "10px", fontSize: "0.8rem", borderTop: "1px dashed var(--border)", paddingTop: "8px" } },
              h("strong", null, "Distribuição Física por Local: "),
              Object.entries(detail.apuracao.detalhesLocalizacaoCondicao).map(([loc, cats]) =>
                `${loc}: [${Object.entries(cats).filter(([_, q]) => q > 0).map(([c, q]) => `${c}: ${q}`).join(", ")}]`
              ).join(" | ")
            )
          ),

          // Seção 2: Desfecho / Status & Ações
          h("div", { style: { border: "1px solid var(--border)", padding: "14px", borderRadius: "8px", marginBottom: "16px" } },
            h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" } },
              h("h4", { style: { margin: 0, fontSize: "0.95rem" } }, "⚖️ Status & Tratativa"),
              h("span", { className: "badge badge-neutral", style: { fontSize: "0.75rem" } }, `Versão ${detail.version}`)
            ),

            // Caso Resolvida ou Sem Causa
            isFinalized && h("div", {
              style: {
                background: detail.status === "RESOLVIDA" ? "rgba(16, 185, 129, 0.08)" : "rgba(107, 114, 128, 0.08)",
                borderLeft: detail.status === "RESOLVIDA" ? "4px solid #10b981" : "4px solid #6b7280",
                padding: "12px",
                borderRadius: "4px",
                marginBottom: "10px"
              }
            },
              h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "8px" } },
                h("div", null,
                  h("strong", { style: { display: "block", fontSize: "0.95rem", color: detail.status === "RESOLVIDA" ? "#065f46" : "#374151" } },
                    detail.status === "RESOLVIDA"
                      ? `Resolvida — Causa: ${CAUSES_MAP[detail.causaConfirmada] || detail.causaConfirmada}`
                      : "Encerrada Sem Causa Identificada"
                  ),
                  h("p", { style: { margin: "4px 0 0 0", fontSize: "0.85rem" } },
                    detail.conclusao || detail.justificativa || "Sem conclusão descrita."
                  ),
                  h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block", marginTop: "4px" } },
                    `Desfecho registrado por ${detail.resolvidoPor || detail.atualizadoPor} em ${detail.resolvidoEm ? new Date(detail.resolvidoEm).toLocaleString("pt-BR") : "—"}`
                  )
                ),
                h("button", {
                  className: "btn btn-secondary",
                  onClick: () => setShowReopenModal(true),
                  style: { fontSize: "0.8rem", padding: "4px 10px" }
                }, "🔄 Reabrir Investigação")
              )
            ),

            // Caso Ativa (PENDENTE, EM_INVESTIGACAO, AGUARDANDO_EVIDENCIA)
            !isFinalized && h("div", null,
              h("div", { style: { display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap", marginBottom: "12px" } },
                detail.causaSuspeita
                  ? h("div", { style: { fontSize: "0.85rem" } },
                      h("span", { className: "hint" }, "Causa Suspeita: "),
                      h("strong", null, CAUSES_MAP[detail.causaSuspeita] || detail.causaSuspeita)
                    )
                  : h("span", { className: "hint", style: { fontSize: "0.85rem" } }, "Nenhuma causa suspeita informada.")
              ),

              h("div", { style: { display: "flex", gap: "8px", flexWrap: "wrap" } },
                detail.status === "PENDENTE" && h("button", {
                  className: "btn btn-primary",
                  onClick: () => setShowStartModal(true),
                  style: { fontSize: "0.85rem" }
                }, "▶ Iniciar Investigação"),

                detail.status !== "PENDENTE" && h("button", {
                  className: "btn btn-secondary",
                  onClick: () => setShowSuspectModal(true),
                  style: { fontSize: "0.85rem" }
                }, "✏️ Alterar Causa Suspeita"),

                detail.status === "EM_INVESTIGACAO" && h("button", {
                  className: "btn btn-secondary",
                  onClick: () => handleChangeStatus("AGUARDANDO_EVIDENCIA", "Aguardando documentação ou foto"),
                  style: { fontSize: "0.85rem" }
                }, "⏸ Aguardar Evidência"),

                detail.status === "AGUARDANDO_EVIDENCIA" && h("button", {
                  className: "btn btn-secondary",
                  onClick: () => handleChangeStatus("EM_INVESTIGACAO", "Retomando investigação"),
                  style: { fontSize: "0.85rem" }
                }, "▶ Retomar Investigação"),

                h("button", {
                  className: "btn btn-primary",
                  onClick: () => setShowResolveModal(true),
                  style: { background: "#10b981", borderColor: "#10b981", fontSize: "0.85rem", fontWeight: "bold" }
                }, "✅ Resolver Divergência"),

                h("button", {
                  className: "btn btn-secondary",
                  onClick: () => setShowUnresolvedModal(true),
                  style: { fontSize: "0.85rem", color: "#6b7280" }
                }, "⚠️ Sem Causa Identificada")
              )
            )
          ),

          // Seção 3: Evidências Coletadas
          h("div", { style: { border: "1px solid var(--border)", padding: "14px", borderRadius: "8px", marginBottom: "16px" } },
            h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" } },
              h("h4", { style: { margin: 0, fontSize: "0.95rem" } }, `📁 Evidências (${detail.evidencias ? detail.evidencias.length : 0})`),
              h("span", { className: "hint", style: { fontSize: "0.75rem" } }, "Documentos, fotos, notas ou observações auditáveis")
            ),

            detail.evidencias && detail.evidencias.length > 0 && h("div", { style: { display: "grid", gap: "8px", marginBottom: "12px" } },
              detail.evidencias.map(ev => h("div", {
                key: ev.id,
                style: { padding: "8px 12px", borderRadius: "6px", background: "rgba(0,0,0,0.02)", border: "1px solid var(--border)" }
              },
                h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "4px" } },
                  h("span", { className: "badge badge-primary", style: { fontSize: "0.7rem" } }, ev.tipo),
                  h("span", { className: "hint", style: { fontSize: "0.75rem" } },
                    `${ev.criadoPor} em ${new Date(ev.criadoEm).toLocaleString("pt-BR")}`
                  )
                ),
                h("p", { style: { margin: "2px 0", fontSize: "0.85rem" } }, ev.descricao),
                ev.referencia && h("span", { className: "hint", style: { fontSize: "0.75rem", fontStyle: "italic" } },
                  `Referência / Anexo: ${ev.referencia}`
                )
              ))
            ),

            (!detail.evidencias || detail.evidencias.length === 0) && h("p", { className: "hint", style: { fontSize: "0.85rem", margin: "6px 0 12px 0" } },
              "Nenhuma evidência anexada até o momento."
            ),

            !isFinalized && h("form", { onSubmit: handleAddEvidence, style: { display: "grid", gap: "8px", background: "rgba(0,0,0,0.01)", padding: "10px", borderRadius: "6px", border: "1px dashed var(--border)" } },
              h("strong", { style: { fontSize: "0.85rem" } }, "+ Anexar Nova Evidência"),
              h("div", { style: { display: "grid", gridTemplateColumns: "180px 1fr", gap: "8px" } },
                h("select", {
                  value: newEvidence.tipo,
                  onChange: e => setNewEvidence({ ...newEvidence, tipo: e.target.value }),
                  style: { padding: "6px 8px", borderRadius: "4px", border: "1px solid var(--border)", fontSize: "0.85rem" }
                },
                  EVIDENCE_TYPES.map(t => h("option", { key: t.id, value: t.id }, t.label))
                ),
                h("input", {
                  type: "text",
                  placeholder: "Descrição objetiva da evidência (ex: Verificado no depósito A-12)",
                  value: newEvidence.descricao,
                  onChange: e => setNewEvidence({ ...newEvidence, descricao: e.target.value }),
                  required: true,
                  style: { padding: "6px 8px", borderRadius: "4px", border: "1px solid var(--border)", fontSize: "0.85rem" }
                })
              ),
              h("div", { style: { display: "flex", gap: "8px" } },
                h("input", {
                  type: "text",
                  placeholder: "Referência opcional (ex: NF 98212, link da foto)",
                  value: newEvidence.referencia,
                  onChange: e => setNewEvidence({ ...newEvidence, referencia: e.target.value }),
                  style: { flex: 1, padding: "6px 8px", borderRadius: "4px", border: "1px solid var(--border)", fontSize: "0.85rem" }
                }),
                h("button", {
                  type: "submit",
                  className: "btn btn-secondary",
                  disabled: addingEvidence || !newEvidence.descricao.trim(),
                  style: { fontSize: "0.85rem", whiteSpace: "nowrap" }
                }, addingEvidence ? "Salvando..." : "Anexar Evidência")
              )
            )
          ),

          // Seção 4: Produtos Relacionados (Vínculos de Inversão / Voltagem)
          h("div", { style: { border: "1px solid var(--border)", padding: "14px", borderRadius: "8px", marginBottom: "16px" } },
            h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" } },
              h("h4", { style: { margin: 0, fontSize: "0.95rem" } }, `🔗 Produtos Relacionados (${detail.vinculos ? detail.vinculos.length : 0})`),
              h("span", { className: "hint", style: { fontSize: "0.75rem" } }, "Inversão de código, voltagem ou movimentação cruzada")
            ),

            detail.vinculos && detail.vinculos.length > 0 && h("div", { style: { display: "grid", gap: "8px", marginBottom: "12px" } },
              detail.vinculos.map(v => h("div", {
                key: v.id,
                style: { padding: "8px 12px", borderRadius: "6px", background: "rgba(0,0,0,0.02)", border: "1px solid var(--border)" }
              },
                h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center" } },
                  h("div", { style: { display: "flex", gap: "8px", alignItems: "center" } },
                    h("strong", { style: { fontSize: "0.9rem" } }, v.relacionadoSku),
                    h("span", { className: "badge badge-warning", style: { fontSize: "0.7rem" } }, v.tipoVinculo)
                  ),
                  h("span", { className: "hint", style: { fontSize: "0.75rem" } },
                    `${v.criadoPor} em ${new Date(v.criadoEm).toLocaleString("pt-BR")}`
                  )
                ),
                v.observacao && h("p", { style: { margin: "4px 0 0 0", fontSize: "0.85rem" } }, v.observacao)
              ))
            ),

            (!detail.vinculos || detail.vinculos.length === 0) && h("p", { className: "hint", style: { fontSize: "0.85rem", margin: "6px 0 12px 0" } },
              "Nenhum produto vinculado a esta divergência."
            ),

            !isFinalized && h("form", { onSubmit: handleAddLink, style: { display: "grid", gap: "8px", background: "rgba(0,0,0,0.01)", padding: "10px", borderRadius: "6px", border: "1px dashed var(--border)" } },
              h("strong", { style: { fontSize: "0.85rem" } }, "+ Vincular Produto Relacionado"),
              h("div", { style: { display: "grid", gridTemplateColumns: "1fr 200px", gap: "8px" } },
                h("select", {
                  value: newLink.inventarioItemRelacionadoId,
                  onChange: e => setNewLink({ ...newLink, inventarioItemRelacionadoId: e.target.value }),
                  required: true,
                  style: { padding: "6px 8px", borderRadius: "4px", border: "1px solid var(--border)", fontSize: "0.85rem" }
                },
                  h("option", { value: "" }, "-- Selecione o item relacionado do inventário --"),
                  investigations
                    .filter(inv => inv.inventarioItemId !== detail.inventarioItemId)
                    .map(inv => h("option", { key: inv.inventarioItemId, value: inv.inventarioItemId },
                      `${inv.sku} - ${inv.descricaoSnapshot || ""} (Dif: ${inv.diferenca != null ? inv.diferenca : "—"})`
                    ))
                ),
                h("select", {
                  value: newLink.tipoVinculo,
                  onChange: e => setNewLink({ ...newLink, tipoVinculo: e.target.value }),
                  style: { padding: "6px 8px", borderRadius: "4px", border: "1px solid var(--border)", fontSize: "0.85rem" }
                },
                  LINK_TYPES.map(l => h("option", { key: l.id, value: l.id }, l.label))
                )
              ),
              h("div", { style: { display: "flex", gap: "8px" } },
                h("input", {
                  type: "text",
                  placeholder: "Observação explicativa do vínculo (ex: SKU com sobra equivalente)",
                  value: newLink.observacao,
                  onChange: e => setNewLink({ ...newLink, observacao: e.target.value }),
                  style: { flex: 1, padding: "6px 8px", borderRadius: "4px", border: "1px solid var(--border)", fontSize: "0.85rem" }
                }),
                h("button", {
                  type: "submit",
                  className: "btn btn-secondary",
                  disabled: addingLink || !newLink.inventarioItemRelacionadoId,
                  style: { fontSize: "0.85rem", whiteSpace: "nowrap" }
                }, addingLink ? "Salvando..." : "Vincular Produto")
              )
            )
          ),

          // Seção 5: Timeline / Linha do Tempo Auditável (Append-only)
          h("div", { style: { border: "1px solid var(--border)", padding: "14px", borderRadius: "8px" } },
            h("h4", { style: { margin: "0 0 10px 0", fontSize: "0.95rem" } }, "📜 Linha do Tempo Auditável"),
            detail.eventos && detail.eventos.length > 0 && h("div", { style: { display: "grid", gap: "6px" } },
              detail.eventos.map(ev => h("div", {
                key: ev.id,
                style: { fontSize: "0.8rem", padding: "6px 10px", borderRadius: "4px", background: "rgba(0,0,0,0.015)", display: "flex", justifyContent: "space-between", alignItems: "center" }
              },
                h("div", { style: { display: "flex", gap: "8px", alignItems: "center" } },
                  h("span", { className: "badge badge-neutral", style: { fontSize: "0.7rem", fontWeight: "bold" } }, ev.tipoEvento),
                  h("span", null,
                    ev.detalhes && ev.detalhes.conclusao
                      ? ev.detalhes.conclusao
                      : ev.detalhes && ev.detalhes.causaConfirmada
                        ? `Causa confirmada: ${CAUSES_MAP[ev.detalhes.causaConfirmada] || ev.detalhes.causaConfirmada}`
                        : ev.detalhes && ev.detalhes.causaSuspeita
                          ? `Causa suspeita: ${CAUSES_MAP[ev.detalhes.causaSuspeita] || ev.detalhes.causaSuspeita}`
                          : ev.detalhes && ev.detalhes.descricao
                            ? ev.detalhes.descricao
                            : ev.tipoEvento
                  )
                ),
                h("span", { className: "hint", style: { fontSize: "0.75rem", whiteSpace: "nowrap" } },
                  `${ev.criadoPor} | ${new Date(ev.criadoEm).toLocaleString("pt-BR")}`
                )
              ))
            )
          )
        )
      )
    ),

    // Modal Iniciar Investigação
    showStartModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "440px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0" } }, "Iniciar Investigação"),
        h("div", { style: { display: "grid", gap: "10px" } },
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Responsável"),
            h("input", {
              type: "text",
              value: startForm.responsavelNome,
              onChange: e => setStartForm({ ...startForm, responsavelNome: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            })
          ),
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Causa Suspeita Inicial (Opcional)"),
            h("select", {
              value: startForm.causaSuspeita,
              onChange: e => setStartForm({ ...startForm, causaSuspeita: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            },
              h("option", { value: "" }, "-- Nenhuma / A definir --"),
              Object.entries(CAUSES_MAP).map(([k, v]) => h("option", { key: k, value: k }, v))
            )
          ),
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Justificativa / Observação Inicial"),
            h("textarea", {
              rows: 3,
              placeholder: "Observações iniciais sobre a divergência...",
              value: startForm.justificativa,
              onChange: e => setStartForm({ ...startForm, justificativa: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            })
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowStartModal(false) }, "Cancelar"),
          h("button", { className: "btn btn-primary", onClick: handleStartInvestigation }, "Iniciar")
        )
      )
    ),

    // Modal Alterar Causa Suspeita
    showSuspectModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "440px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0" } }, "Atualizar Causa Suspeita"),
        h("div", { style: { display: "grid", gap: "10px" } },
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Nova Causa Suspeita"),
            h("select", {
              value: suspectForm.causaSuspeita,
              onChange: e => setSuspectForm({ ...suspectForm, causaSuspeita: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            },
              h("option", { value: "" }, "-- Selecione a causa suspeita --"),
              Object.entries(CAUSES_MAP).map(([k, v]) => h("option", { key: k, value: k }, v))
            )
          ),
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Motivo da Alteração"),
            h("textarea", {
              rows: 3,
              placeholder: "Descreva por que a causa suspeita foi alterada...",
              value: suspectForm.justificativa,
              onChange: e => setSuspectForm({ ...suspectForm, justificativa: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            })
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowSuspectModal(false) }, "Cancelar"),
          h("button", { className: "btn btn-primary", onClick: handleUpdateSuspectCause, disabled: !suspectForm.causaSuspeita }, "Salvar")
        )
      )
    ),

    // Modal Resolver Divergência
    showResolveModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "480px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0", color: "#10b981" } }, "✅ Resolver Divergência"),
        h("p", { className: "hint", style: { fontSize: "0.85rem", marginTop: 0 } },
          "A resolução encerra a investigação com uma causa confirmada e justificativa auditável."
        ),
        h("div", { style: { display: "grid", gap: "12px" } },
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Causa Confirmada *"),
            h("select", {
              value: resolveForm.causaConfirmada,
              onChange: e => setResolveForm({ ...resolveForm, causaConfirmada: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            },
              Object.entries(CAUSES_MAP)
                .filter(([k]) => k !== "NAO_IDENTIFICADA")
                .map(([k, v]) => h("option", { key: k, value: k }, v))
            )
          ),
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Conclusão Detalhada * (mínimo 5 caracteres)"),
            h("textarea", {
              rows: 4,
              placeholder: "Descreva a conclusão da investigação e ações tomadas...",
              value: resolveForm.conclusao,
              onChange: e => setResolveForm({ ...resolveForm, conclusao: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            })
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowResolveModal(false) }, "Cancelar"),
          h("button", {
            className: "btn btn-primary",
            onClick: handleResolveInvestigation,
            disabled: !resolveForm.conclusao || resolveForm.conclusao.trim().length < 5,
            style: { background: "#10b981", borderColor: "#10b981" }
          }, "Confirmar Resolução")
        )
      )
    ),

    // Modal Encerrar Sem Causa Identificada
    showUnresolvedModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "480px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0", color: "#6b7280" } }, "⚠️ Encerrar Sem Causa Identificada"),
        h("p", { className: "hint", style: { fontSize: "0.85rem", marginTop: 0 } },
          "Use esta opção quando todas as verificações foram esgotadas e a causa da divergência não pôde ser determinada."
        ),
        h("div", { style: { display: "grid", gap: "10px" } },
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Justificativa Explicativa * (mínimo 10 caracteres)"),
            h("textarea", {
              rows: 4,
              placeholder: "Descreva os procedimentos efetuados e a razão pela qual não foi possível identificar a causa...",
              value: unresolvedForm.conclusao,
              onChange: e => setUnresolvedForm({ ...unresolvedForm, conclusao: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            })
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowUnresolvedModal(false) }, "Cancelar"),
          h("button", {
            className: "btn btn-secondary",
            onClick: handleCloseUnresolved,
            disabled: !unresolvedForm.conclusao || unresolvedForm.conclusao.trim().length < 10,
            style: { fontWeight: "bold" }
          }, "Encerrar Sem Causa")
        )
      )
    ),

    // Modal Reabrir Investigação
    showReopenModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "440px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0" } }, "🔄 Reabrir Investigação"),
        h("p", { className: "hint", style: { fontSize: "0.85rem", marginTop: 0 } },
          "Investigações finalizadas não podem ser alteradas silenciosamente. A reabertura registra um evento explícito na linha do tempo."
        ),
        h("div", { style: { display: "grid", gap: "10px" } },
          h("div", null,
            h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block", marginBottom: "4px" } }, "Justificativa da Reabertura * (mínimo 10 caracteres)"),
            h("textarea", {
              rows: 3,
              placeholder: "Descreva a razão pela qual a investigação está sendo reaberta...",
              value: reopenForm.justificativa,
              onChange: e => setReopenForm({ ...reopenForm, justificativa: e.target.value }),
              style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
            })
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowReopenModal(false) }, "Cancelar"),
          h("button", {
            className: "btn btn-primary",
            onClick: handleReopenInvestigation,
            disabled: !reopenForm.justificativa || reopenForm.justificativa.trim().length < 10
          }, "Confirmar Reabertura")
        )
      )
    )
  );
}

function FechamentoScreen({
  inventory,
  branchCode,
  request,
  user,
  onBack,
  onClosed,
  onGoToAudit,
  onGoToInvestigations
}) {
  const [validation, setValidation] = useState(null);
  const [loading, setLoading] = useState(true);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");

  const [showNormalModal, setShowNormalModal] = useState(false);
  const [showExceptionalModal, setShowExceptionalModal] = useState(false);
  const [justificativaExcepcional, setJustificativaExcepcional] = useState("");

  const isAdmin = user && (
    (user.role && user.role.toLowerCase() === "admin") ||
    (user.perfil && user.perfil.toLowerCase() === "admin")
  );

  useEffect(() => {
    loadValidation();
  }, [inventory.id, branchCode]);

  async function loadValidation() {
    setLoading(true);
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/fechamento/validacao?branchCode=${encodeURIComponent(branchCode)}`);
      setValidation(res);
    } catch (err) {
      setError(err.message || "Erro ao consultar validação de fechamento.");
    } finally {
      setLoading(false);
    }
  }

  async function handleClose(tipo, justificativa = null) {
    setClosing(true);
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/fechar?branchCode=${encodeURIComponent(branchCode)}`, {
        method: "POST",
        body: {
          branchCode,
          tipo,
          tipoFechamento: tipo,
          justificativa: justificativa ? justificativa.trim() : null
        }
      });
      setFeedback(`Inventário encerrado com sucesso (${tipo})!`);
      setShowNormalModal(false);
      setShowExceptionalModal(false);
      if (onClosed) {
        onClosed(res);
      }
    } catch (err) {
      setError(err.message || "Erro ao encerrar inventário.");
    } finally {
      setClosing(false);
    }
  }

  const pendencias = validation?.pendencias || [];
  const resumo = validation?.resumo || {};
  const podeFechar = validation?.podeFechar === true;

  return h("div", { className: "fechamento-screen-view" },
    // Header
    h("div", { className: "card-panel", style: { padding: "14px 18px", marginBottom: "14px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" } },
          h("button", { className: "btn btn-secondary", onClick: onBack, style: { fontSize: "0.85rem", padding: "6px 12px" } },
            "← Voltar"
          ),
          h("h3", { style: { margin: 0 } }, "🏁 Fechamento Formal do Inventário"),
          h("span", { className: "badge badge-neutral" }, inventory.nome),
          h("span", { className: "badge badge-primary" }, `Filial ${branchCode}`)
        ),
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center" } },
          h("button", { className: "btn btn-secondary", onClick: loadValidation, disabled: loading, style: { fontSize: "0.85rem" } },
            "↻ Atualizar Validação"
          )
        )
      )
    ),

    feedback && h("div", { className: "notice-banner notice-success", style: { margin: "10px 0" } },
      feedback,
      h("button", { className: "btn-link", onClick: () => setFeedback(""), style: { float: "right" } }, "✕")
    ),

    error && h("div", { className: "notice-banner notice-danger", style: { margin: "10px 0" } },
      error,
      h("button", { className: "btn-link", onClick: () => setError(""), style: { float: "right" } }, "✕")
    ),

    loading && h("div", { className: "card-panel", style: { textAlign: "center", padding: "40px" } },
      "Analisando regras de fechamento da sessão de inventário..."
    ),

    !loading && validation && h("div", { style: { display: "grid", gap: "14px" } },
      // Banner de Status da Validação
      h("div", {
        className: `card-panel ${podeFechar ? "status-ok" : "status-blocked"}`,
        style: {
          padding: "16px",
          borderLeft: `6px solid ${podeFechar ? "#10b981" : "#ef4444"}`,
          background: podeFechar ? "rgba(16, 185, 129, 0.05)" : "rgba(239, 68, 68, 0.05)"
        }
      },
        h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
          h("div", null,
            h("h4", { style: { margin: "0 0 6px 0", color: podeFechar ? "#10b981" : "#ef4444", fontSize: "1.1rem" } },
              podeFechar ? "✔ Sessão Apta para Fechamento Formal Normal" : "⛔ Pendências Impeditivas Detectadas"
            ),
            h("p", { className: "hint", style: { margin: 0, fontSize: "0.9rem" } },
              podeFechar
                ? "Todas as rodadas foram apuradas, sem divergências não investigadas ou pendências ativas. O fechamento gerará o snapshot histórico definitivo e imutável."
                : "Existem pendências operacionais ativas que impedem o fechamento normal. Resolva-as ou utilize o Fechamento Excepcional (requer permissão de Administrador e justificativa formal)."
            )
          ),
          h("span", {
            className: `badge ${podeFechar ? "badge-success" : "badge-danger"}`,
            style: { padding: "8px 14px", fontSize: "0.9rem", fontWeight: "bold" }
          }, podeFechar ? "APTO PARA FECHAR" : `${pendencias.length} PENDÊNCIA(S)`)
        )
      ),

      // Resumo de Métricas Operacionais
      h("div", { className: "card-panel", style: { padding: "16px" } },
        h("h4", { style: { margin: "0 0 12px 0", fontSize: "0.95rem", color: "var(--text-secondary)" } }, "Balanço Operacional da Sessão"),
        h("div", { style: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(130px, 1fr))", gap: "10px" } },
          h("div", { style: { padding: "10px", background: "rgba(0,0,0,0.02)", borderRadius: "6px", textAlign: "center" } },
            h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Total SKUs"),
            h("strong", { style: { fontSize: "1.2rem" } }, resumo.totalSkus ?? 0)
          ),
          h("div", { style: { padding: "10px", background: "rgba(16, 185, 129, 0.08)", borderRadius: "6px", textAlign: "center" } },
            h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Conformes"),
            h("strong", { style: { fontSize: "1.2rem", color: "#10b981" } }, resumo.conformes ?? 0)
          ),
          h("div", { style: { padding: "10px", background: "rgba(239, 68, 68, 0.08)", borderRadius: "6px", textAlign: "center" } },
            h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Divergências"),
            h("strong", { style: { fontSize: "1.2rem", color: "#ef4444" } }, resumo.divergencias ?? 0)
          ),
          h("div", { style: { padding: "10px", background: "rgba(107, 114, 128, 0.08)", borderRadius: "6px", textAlign: "center" } },
            h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Não Contados"),
            h("strong", { style: { fontSize: "1.2rem", color: "#6b7280" } }, resumo.naoContados ?? 0)
          ),
          h("div", { style: { padding: "10px", background: "rgba(59, 130, 246, 0.08)", borderRadius: "6px", textAlign: "center" } },
            h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Investigações Resolvidas"),
            h("strong", { style: { fontSize: "1.2rem", color: "#3b82f6" } }, (resumo.investigacoesResolvidas ?? 0) + (resumo.investigacoesSemCausa ?? 0))
          ),
          h("div", { style: { padding: "10px", background: "rgba(245, 158, 11, 0.08)", borderRadius: "6px", textAlign: "center" } },
            h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, "Investigações Pendentes"),
            h("strong", { style: { fontSize: "1.2rem", color: "#f59e0b" } }, resumo.investigacoesPendentes ?? 0)
          )
        )
      ),

      // Lista Detalhada de Pendências (se houver)
      pendencias.length > 0 && h("div", { className: "card-panel", style: { padding: "16px" } },
        h("h4", { style: { margin: "0 0 12px 0", color: "#ef4444" } }, "⚠️ Pendências Encontradas"),
        h("div", { style: { display: "grid", gap: "10px" } },
          pendencias.map((p, idx) => {
            const isRound = p.tipo === "RODADA_ATIVA" || p.tipo === "SEM_APURACAO_R1" || p.tipo === "RODADA_2_PENDENTE";
            const isInvestigation = p.tipo === "DIVERGENCIA_SEM_INVESTIGACAO" || p.tipo === "INVESTIGACOES_PENDENTES";
            const isUncounted = p.tipo === "ITENS_NAO_CONTADOS";

            return h("div", {
              key: idx,
              style: {
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                padding: "12px 14px",
                background: "rgba(239, 68, 68, 0.04)",
                border: "1px solid rgba(239, 68, 68, 0.2)",
                borderRadius: "6px",
                flexWrap: "wrap",
                gap: "10px"
              }
            },
              h("div", { style: { display: "grid", gap: "3px" } },
                h("div", { style: { display: "flex", alignItems: "center", gap: "6px" } },
                  h("span", { className: "badge badge-danger", style: { fontSize: "0.75rem" } }, p.tipo),
                  h("strong", { style: { fontSize: "0.95rem" } }, p.mensagem)
                ),
                p.totalAfetado != null && h("span", { className: "hint", style: { fontSize: "0.85rem" } },
                  `Total afetado: ${p.totalAfetado} item(ns)`
                )
              ),
              h("div", null,
                isRound && h("button", {
                  className: "btn btn-secondary",
                  onClick: onGoToAudit,
                  style: { fontSize: "0.85rem", padding: "6px 12px" }
                }, "Ir para Contagem/Apuração →"),

                isInvestigation && h("button", {
                  className: "btn btn-secondary",
                  onClick: onGoToInvestigations,
                  style: { fontSize: "0.85rem", padding: "6px 12px" }
                }, "Ir para Investigações →"),

                isUncounted && h("button", {
                  className: "btn btn-secondary",
                  onClick: onGoToAudit,
                  style: { fontSize: "0.85rem", padding: "6px 12px" }
                }, "Ver Itens Não Contados →")
              )
            );
          })
        )
      ),

      // Painel de Ações de Fechamento
      h("div", { className: "card-panel", style: { padding: "18px", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "12px" } },
        h("div", null,
          h("strong", { style: { display: "block", fontSize: "1rem" } }, "Ações de Fechamento"),
          h("span", { className: "hint", style: { fontSize: "0.85rem" } },
            "O fechamento encerra a sessão formalmente e bloqueia quaisquer alterações posteriores."
          )
        ),
        h("div", { style: { display: "flex", gap: "10px", flexWrap: "wrap" } },
          h("button", {
            className: "btn btn-primary",
            disabled: !podeFechar || closing,
            onClick: () => setShowNormalModal(true),
            style: {
              background: podeFechar ? "#10b981" : "#9ca3af",
              borderColor: podeFechar ? "#10b981" : "#9ca3af",
              fontWeight: "bold",
              padding: "10px 18px"
            }
          }, closing ? "Encerrando..." : "🔒 Fechamento Normal"),

          h("button", {
            className: "btn btn-danger-outline",
            disabled: closing,
            onClick: () => {
              if (!isAdmin) {
                alert("O Fechamento Excepcional requer perfil de Administrador.");
                return;
              }
              setShowExceptionalModal(true);
            },
            title: isAdmin ? "Fechar sessão gravando snapshot de pendências" : "Requer perfil de Administrador",
            style: { fontWeight: "bold", padding: "10px 18px" }
          }, "⚠️ Fechamento Excepcional (Admin)")
        )
      )
    ),

    // Modal Fechamento Normal
    showNormalModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "480px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0", color: "#10b981" } }, "🔒 Confirmar Fechamento Formal Normal"),
        h("div", { style: { display: "grid", gap: "10px", fontSize: "0.9rem" } },
          h("p", { style: { margin: 0 } }, "Atenção: Ao confirmar o fechamento formal:"),
          h("ul", { style: { margin: "4px 0 12px 18px", padding: 0 } },
            h("li", null, "O status da sessão passará para ", h("strong", null, "ENCERRADO"), "."),
            h("li", null, "A sessão se tornará ", h("strong", null, "estritamente imutável"), " (nenhuma contagem, rodada ou investigação poderá ser criada ou modificada)."),
            h("li", null, "Os resultados consolidados de sobra, falta e acurácia serão gravados no relatório definitivo.")
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowNormalModal(false), disabled: closing }, "Cancelar"),
          h("button", {
            className: "btn btn-primary",
            onClick: () => handleClose("NORMAL"),
            disabled: closing,
            style: { background: "#10b981", borderColor: "#10b981", fontWeight: "bold" }
          }, closing ? "Fechando..." : "Confirmar Fechamento")
        )
      )
    ),

    // Modal Fechamento Excepcional
    showExceptionalModal && h("div", { className: "modal-backdrop", style: { position: "fixed", top: 0, left: 0, right: 0, bottom: 0, background: "rgba(0,0,0,0.5)", display: "flex", justifyContent: "center", alignItems: "center", zIndex: 1000 } },
      h("div", { className: "card-panel modal-card", style: { width: "100%", maxWidth: "520px", padding: "20px" } },
        h("h3", { style: { margin: "0 0 12px 0", color: "#ef4444" } }, "⚠️ Confirmar Fechamento Excepcional"),
        h("p", { className: "hint", style: { fontSize: "0.85rem", marginTop: 0 } },
          "O Fechamento Excepcional encerra a sessão mesmo com pendências impeditivas. Esta operação é restrita a Administradores, auditada e irreversível."
        ),
        pendencias.length > 0 && h("div", { style: { padding: "10px", background: "rgba(239, 68, 68, 0.08)", borderRadius: "6px", marginBottom: "12px" } },
          h("strong", { style: { fontSize: "0.85rem", color: "#ef4444", display: "block", marginBottom: "4px" } }, "Snapshot de Pendências que serão registradas:"),
          h("ul", { style: { margin: "0 0 0 16px", padding: 0, fontSize: "0.8rem" } },
            pendencias.map((p, idx) => h("li", { key: idx }, `${p.tipo}: ${p.mensagem}`))
          )
        ),
        h("div", { style: { display: "grid", gap: "8px" } },
          h("label", { style: { fontSize: "0.85rem", fontWeight: "bold", display: "block" } },
            "Justificativa Formal da Auditoria * (mínimo 15 caracteres):"
          ),
          h("textarea", {
            rows: 4,
            placeholder: "Descreva formalmente o motivo pelo qual a sessão está sendo encerrada com pendências...",
            value: justificativaExcepcional,
            onChange: e => setJustificativaExcepcional(e.target.value),
            style: { width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid var(--border)", boxSizing: "border-box" }
          }),
          h("div", { style: { fontSize: "0.75rem", color: justificativaExcepcional.trim().length >= 15 ? "#10b981" : "#ef4444", textAlign: "right" } },
            `${justificativaExcepcional.trim().length} / 15 caracteres mínimos`
          )
        ),
        h("div", { style: { display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" } },
          h("button", { className: "btn btn-secondary", onClick: () => setShowExceptionalModal(false), disabled: closing }, "Cancelar"),
          h("button", {
            className: "btn btn-danger",
            onClick: () => handleClose("EXCEPCIONAL", justificativaExcepcional),
            disabled: closing || justificativaExcepcional.trim().length < 15,
            style: { fontWeight: "bold" }
          }, closing ? "Encerrando..." : "Confirmar Fechamento Excepcional")
        )
      )
    )
  );
}

function HistoricoResultadoScreen({ inventory, branchCode, request, user, onBack }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [activeTab, setActiveTab] = useState("itens"); // 'itens' | 'eventos'
  const [statusFilter, setStatusFilter] = useState("TODOS");
  const [searchQuery, setSearchQuery] = useState("");

  useEffect(() => {
    loadData();
  }, [inventory.id, branchCode]);

  async function loadData() {
    setLoading(true);
    setError("");
    try {
      const res = await request(`/api/inventarios/${inventory.id}/detalhe-historico?branchCode=${encodeURIComponent(branchCode)}`);
      setData(res);
    } catch (err) {
      setError(err.message || "Erro ao carregar histórico consolidado.");
    } finally {
      setLoading(false);
    }
  }

  if (loading) {
    return h("div", { className: "card-panel", style: { textAlign: "center", padding: "40px" } },
      "Carregando resultado consolidado e histórico oficial do inventário..."
    );
  }

  if (!data || !data.resultado) {
    return h("div", { className: "card-panel", style: { padding: "20px", textAlign: "center" } },
      error && h("div", { className: "notice-banner notice-danger" }, error),
      h("button", { className: "btn btn-secondary", onClick: onBack, style: { marginTop: "12px" } }, "← Voltar")
    );
  }

  const { resultado, itens, eventos } = data;
  const isExcepcional = resultado.tipoFechamento === "EXCEPCIONAL";

  // Calcular acurácia
  const totalValidos = resultado.totalItens - resultado.itensNaoContados;
  const totalConformesFinal = resultado.itensConformesR1 + resultado.itensConformesAposR2;
  const acuraciaPercent = totalValidos > 0
    ? Math.round((totalConformesFinal / totalValidos) * 100)
    : 0;

  // Filtragem dos itens
  const filteredItems = (itens || []).filter(item => {
    if (statusFilter !== "TODOS") {
      if (statusFilter === "CONFORME" && !(item.estadoFinal === "CONFORME" || item.estadoFinal === "CONFORME_APOS_RECONTAGEM")) return false;
      if (statusFilter === "DIVERGENTE" && item.estadoFinal !== "DIVERGENCIA_CONFIRMADA") return false;
      if (statusFilter === "NAO_CONTADO" && item.estadoFinal !== "NAO_CONTADO") return false;
      if (statusFilter === "FALTA" && item.tipoDivergencia !== "FALTA") return false;
      if (statusFilter === "SOBRA" && item.tipoDivergencia !== "SOBRA") return false;
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      const matchSku = item.sku && item.sku.toLowerCase().includes(q);
      const matchDesc = item.descricao && item.descricao.toLowerCase().includes(q);
      return matchSku || matchDesc;
    }
    return true;
  });

  function formatDuration(seconds) {
    if (seconds == null) return "-";
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    if (hrs > 0) return `${hrs}h ${mins}m`;
    if (mins > 0) return `${mins}m ${secs}s`;
    return `${secs}s`;
  }

  return h("div", { className: "historico-resultado-view" },
    // Header
    h("div", { className: "card-panel", style: { padding: "14px 18px", marginBottom: "14px" } },
      h("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" } },
          h("button", { className: "btn btn-secondary", onClick: onBack, style: { fontSize: "0.85rem", padding: "6px 12px" } },
            "← Voltar aos Inventários"
          ),
          h("h3", { style: { margin: 0 } }, "🏆 Resultado & Histórico Oficial"),
          h("span", { className: "badge badge-status badge-encerrado", style: { fontWeight: "bold" } }, "ENCERRADO"),
          h("span", { className: `badge ${isExcepcional ? "badge-warning" : "badge-success"}` },
            isExcepcional ? "FECHAMENTO EXCEPCIONAL" : "FECHAMENTO NORMAL"
          ),
          h("span", { className: "badge badge-primary" }, `Filial ${branchCode}`)
        ),
        h("button", { className: "btn btn-secondary", onClick: loadData, style: { fontSize: "0.85rem" } },
          "↻ Atualizar"
        )
      ),

      h("div", { style: { marginTop: "10px" } },
        h("h4", { style: { margin: "0 0 4px" } }, resultado.inventarioNome || inventory.nome),
        h("p", { className: "hint", style: { margin: 0, fontSize: "0.85rem" } },
          `Encerrado por ${resultado.fechadoPorNome} em ${new Date(resultado.fechadoEm).toLocaleString("pt-BR")}`
        )
      )
    ),

    // Banner de Fechamento Excepcional (se houver)
    isExcepcional && h("div", {
      className: "card-panel",
      style: {
        padding: "16px",
        marginBottom: "14px",
        borderLeft: "6px solid #f59e0b",
        background: "rgba(245, 158, 11, 0.05)"
      }
    },
      h("h4", { style: { margin: "0 0 6px 0", color: "#f59e0b" } }, "⚠️ Inventário Fechado em Caráter Excepcional"),
      h("p", { style: { margin: "0 0 8px 0", fontSize: "0.9rem" } },
        h("strong", null, "Justificativa da Auditoria: "),
        resultado.justificativaExcepcional || "-"
      ),
      resultado.pendenciasSnapshot && resultado.pendenciasSnapshot.length > 0 && h("div", null,
        h("strong", { style: { fontSize: "0.85rem", color: "#6b7280", display: "block", marginBottom: "4px" } }, "Pendências Registradas no Snapshot de Fechamento:"),
        h("ul", { style: { margin: "0 0 0 16px", padding: 0, fontSize: "0.85rem", color: "#374151" } },
          resultado.pendenciasSnapshot.map((p, idx) => h("li", { key: idx }, `${p.tipo || p.code || 'PENDENCIA'}: ${p.mensagem || p.message || JSON.stringify(p)}`))
        )
      )
    ),

    // Métricas Consolidadas (Cards)
    h("div", { className: "historico-metrics-grid", style: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(130px, 1fr))", gap: "10px", marginBottom: "14px" } },
      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Total SKUs"),
        h("strong", { style: { fontSize: "1.4rem" } }, resultado.totalItens)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #10b981" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Total Conformes"),
        h("strong", { style: { fontSize: "1.4rem", color: "#10b981" } }, totalConformesFinal),
        h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, `R1: ${resultado.itensConformesR1} | R2: ${resultado.itensConformesAposR2}`)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #ef4444" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Divergências Confirmadas"),
        h("strong", { style: { fontSize: "1.4rem", color: "#ef4444" } }, resultado.divergenciasConfirmadas)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #6b7280" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Não Contados"),
        h("strong", { style: { fontSize: "1.4rem", color: "#6b7280" } }, resultado.itensNaoContados)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center", borderLeft: "4px solid #3b82f6" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Acurácia Global"),
        h("strong", { style: { fontSize: "1.4rem", color: "#3b82f6" } }, `${acuraciaPercent}%`)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Falta / Sobra (Un)"),
        h("span", { style: { fontSize: "1.1rem", fontWeight: "bold" } },
          h("span", { style: { color: "#ef4444" } }, `-${resultado.quantidadeFalta}`),
          " / ",
          h("span", { style: { color: "#10b981" } }, `+${resultado.quantidadeSobra}`)
        ),
        h("span", { className: "hint", style: { fontSize: "0.75rem", display: "block" } }, `${resultado.itensComFalta} falta | ${resultado.itensComSobra} sobra`)
      ),

      h("div", { className: "card-panel", style: { padding: "12px", textAlign: "center" } },
        h("span", { className: "hint", style: { fontSize: "0.8rem", display: "block" } }, "Duração da Sessão"),
        h("strong", { style: { fontSize: "1.1rem" } }, formatDuration(resultado.duracaoSegundos))
      )
    ),

    // Abas de Navegação
    h("div", { style: { display: "flex", gap: "8px", marginBottom: "12px", flexWrap: "wrap" } },
      h("button", {
        className: `btn ${activeTab === "itens" ? "btn-primary" : "btn-secondary"}`,
        onClick: () => setActiveTab("itens"),
        style: { borderRadius: "20px", fontSize: "0.9rem", padding: "6px 16px" }
      }, `📦 Itens Auditados & Resultado (${itens ? itens.length : 0})`),

      h("button", {
        className: `btn ${activeTab === "eventos" ? "btn-primary" : "btn-secondary"}`,
        onClick: () => setActiveTab("eventos"),
        style: { borderRadius: "20px", fontSize: "0.9rem", padding: "6px 16px" }
      }, `📜 Linha do Tempo de Auditoria (${eventos ? eventos.length : 0})`)
    ),

    // Aba Itens
    activeTab === "itens" && h("div", null,
      // Filtros
      h("div", { className: "card-panel", style: { padding: "12px", marginBottom: "12px", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "10px" } },
        h("div", { style: { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" } },
          h("input", {
            type: "search",
            placeholder: "Pesquisar por SKU ou descrição...",
            value: searchQuery,
            onChange: e => setSearchQuery(e.target.value),
            style: { padding: "6px 12px", borderRadius: "4px", border: "1px solid var(--border)", width: "240px" }
          }),
          [
            { id: "TODOS", label: "Todos" },
            { id: "CONFORME", label: "Conformes" },
            { id: "DIVERGENTE", label: "Divergentes" },
            { id: "FALTA", label: "Faltas" },
            { id: "SOBRA", label: "Sobras" },
            { id: "NAO_CONTADO", label: "Não Contados" }
          ].map(f => h("button", {
            key: f.id,
            className: `btn ${statusFilter === f.id ? "btn-primary" : "btn-secondary"}`,
            onClick: () => setStatusFilter(f.id),
            style: { fontSize: "0.8rem", padding: "4px 10px", borderRadius: "14px" }
          }, f.label))
        ),
        h("span", { className: "hint", style: { fontSize: "0.85rem" } },
          `Exibindo ${filteredItems.length} de ${itens ? itens.length : 0} itens`
        )
      ),

      // Tabela de Itens do Resultado
      h("div", { className: "card-panel", style: { padding: 0, overflowX: "auto" } },
        h("table", { className: "table", style: { width: "100%", borderCollapse: "collapse" } },
          h("thead", null,
            h("tr", { style: { borderBottom: "1px solid var(--border)", background: "rgba(0,0,0,0.02)" } },
              h("th", { style: { padding: "10px", textAlign: "left" } }, "SKU"),
              h("th", { style: { padding: "10px", textAlign: "left" } }, "Descrição"),
              h("th", { style: { padding: "10px", textAlign: "right" } }, "Saldo Esp."),
              h("th", { style: { padding: "10px", textAlign: "right" } }, "R1 Físico"),
              h("th", { style: { padding: "10px", textAlign: "right" } }, "R2 Físico"),
              h("th", { style: { padding: "10px", textAlign: "right" } }, "Físico Final"),
              h("th", { style: { padding: "10px", textAlign: "right" } }, "Diferença"),
              h("th", { style: { padding: "10px", textAlign: "center" } }, "Status Final"),
              h("th", { style: { padding: "10px", textAlign: "left" } }, "Investigação / Conclusão")
            )
          ),
          h("tbody", null,
            filteredItems.length === 0 && h("tr", null,
              h("td", { colSpan: 9, style: { textAlign: "center", padding: "30px" }, className: "hint" },
                "Nenhum item corresponde aos critérios de pesquisa."
              )
            ),
            filteredItems.map(item => {
              const diff = item.diferencaFinal;
              const isConforme = item.estadoFinal === "CONFORME" || item.estadoFinal === "CONFORME_APOS_RECONTAGEM";
              const isDivergente = item.estadoFinal === "DIVERGENCIA_CONFIRMADA";

              let diffColor = "var(--text-secondary)";
              let diffPrefix = "";
              if (diff > 0) {
                diffColor = "#10b981";
                diffPrefix = "+";
              } else if (diff < 0) {
                diffColor = "#ef4444";
              }

              return h("tr", { key: item.id, style: { borderBottom: "1px solid var(--border)" } },
                h("td", { style: { padding: "10px", fontWeight: "bold" } }, item.sku),
                h("td", { style: { padding: "10px" } }, item.descricao),
                h("td", { style: { padding: "10px", textAlign: "right", color: "var(--text-secondary)" } }, item.saldoSnapshot),
                h("td", { style: { padding: "10px", textAlign: "right" } }, item.r1Fisico != null ? `${item.r1Fisico} un` : "-"),
                h("td", { style: { padding: "10px", textAlign: "right" } }, item.houveR2 ? (item.r2Fisico != null ? `${item.r2Fisico} un` : "-") : "N/A"),
                h("td", { style: { padding: "10px", textAlign: "right", fontWeight: "bold" } },
                  item.quantidadeFisicaFinal != null ? `${item.quantidadeFisicaFinal} un` : "-"
                ),
                h("td", { style: { padding: "10px", textAlign: "right", fontWeight: "bold", color: diffColor } },
                  diff != null ? `${diffPrefix}${diff}` : "-"
                ),
                h("td", { style: { padding: "10px", textAlign: "center" } },
                  h("span", {
                    className: `badge ${isConforme ? "badge-success" : isDivergente ? "badge-danger" : "badge-neutral"}`,
                    style: { fontSize: "0.75rem" }
                  }, item.estadoFinal)
                ),
                h("td", { style: { padding: "10px", fontSize: "0.85rem" } },
                  item.causaConfirmada && h("div", { style: { fontWeight: "bold", color: "#374151" } },
                    CAUSES_MAP[item.causaConfirmada] || item.causaConfirmada
                  ),
                  item.conclusao && h("div", { className: "hint", style: { fontSize: "0.8rem" } },
                    item.conclusao
                  ),
                  !item.causaConfirmada && !item.conclusao && h("span", { className: "hint" }, "-")
                )
              );
            })
          )
        )
      )
    ),

    // Aba Eventos (Linha do Tempo)
    activeTab === "eventos" && h("div", { className: "card-panel", style: { padding: "20px" } },
      h("h4", { style: { margin: "0 0 16px 0" } }, "Linha do Tempo Auditável da Sessão"),
      (!eventos || eventos.length === 0) && h("p", { className: "hint" }, "Nenhum evento registrado."),
      eventos && eventos.length > 0 && h("div", { style: { display: "grid", gap: "12px" } },
        eventos.map((ev, idx) => {
          let badgeColor = "badge-neutral";
          if (ev.tipoEvento === "INVENTARIO_ENCERRADO") badgeColor = "badge-danger";
          else if (ev.tipoEvento === "INICIADO" || ev.tipoEvento === "RODADA_INICIADA") badgeColor = "badge-primary";
          else if (ev.tipoEvento === "RODADA_ENCERRADA") badgeColor = "badge-warning";
          else if (ev.tipoEvento === "RECONTAGEM_CRIADA") badgeColor = "badge-warning";

          return h("div", {
            key: ev.id || idx,
            style: {
              display: "flex",
              gap: "14px",
              padding: "12px",
              background: "rgba(0,0,0,0.02)",
              borderRadius: "6px",
              borderLeft: "4px solid var(--primary, #3b82f6)"
            }
          },
            h("div", { style: { minWidth: "140px" } },
              h("span", { className: `badge ${badgeColor}`, style: { display: "inline-block", marginBottom: "4px" } }, ev.tipoEvento),
              h("div", { className: "hint", style: { fontSize: "0.75rem" } },
                new Date(ev.criadoEm).toLocaleString("pt-BR")
              )
            ),
            h("div", { style: { flex: 1 } },
              h("div", { style: { fontSize: "0.85rem", fontWeight: "bold" } }, `Operador: ${ev.criadoPor}`),
              ev.detalhes && h("div", { style: { fontSize: "0.8rem", color: "var(--text-secondary)", marginTop: "4px" } },
                typeof ev.detalhes === "object" ? JSON.stringify(ev.detalhes) : ev.detalhes
              )
            )
          );
        })
      )
    )
  );
}

if (typeof window !== "undefined") {
  window.MNCheckInventarios = {
    InventariosManager,
    InventarioContagemScreen,
    ApuracaoScreen,
    InvestigacoesScreen,
    FechamentoScreen,
    HistoricoResultadoScreen,
    CreateInventoryModal
  };
}
