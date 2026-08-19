import { enterpriseJson, isNetworkFailure } from "./api.js";

const React = window.React;
const h = React.createElement;
const QUEUE_KEY = "mnCheckEnterpriseQueue";

function idempotency(prefix = "op") {
  return `${prefix}:${crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`}`;
}

async function fileSha256(file) {
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function readQueue() {
  try { return JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]"); }
  catch (_) { return []; }
}

function saveQueue(queue) {
  localStorage.setItem(QUEUE_KEY, JSON.stringify(queue));
}

async function mutate(path, body, prefix, queueOffline = false, method = "POST") {
  const operation = { path, body, method, key: idempotency(prefix), createdAt: new Date().toISOString() };
  try {
    return await enterpriseJson(path, { method: operation.method, body, idempotencyKey: operation.key });
  } catch (error) {
    if (!queueOffline || !isNetworkFailure(error)) throw error;
    const queue = readQueue();
    queue.push(operation);
    saveQueue(queue);
    return { queued: true };
  }
}

async function syncQueue() {
  if (!navigator.onLine) return 0;
  const queue = readQueue();
  const pending = [];
  let completed = 0;
  for (const operation of queue) {
    try {
      await enterpriseJson(operation.path, {
        method: operation.method,
        body: operation.body,
        idempotencyKey: operation.key
      });
      completed += 1;
    } catch (error) {
      pending.push({ ...operation, lastError: error.message });
    }
  }
  saveQueue(pending);
  return completed;
}

function number(value) {
  return new Intl.NumberFormat("pt-BR").format(Number(value || 0));
}

function date(value) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
  }).format(new Date(value));
}

function effectiveExpected(item) {
  return Math.max(0, Number(item?.quantidade_esperada || 0) - Number(item?.quantidade_cancelada || 0));
}

function humanStatus(value) {
  return String(value || "—").toLowerCase().replaceAll("_", " ");
}

function statusTone(value) {
  const text = String(value || "");
  if (/FINALIZ|EXPEDIDO|RECEBIDA$|RESOLVIDA|CONFERIDO/.test(text)) return "success";
  if (/DIVERG|QUARENTENA|CANCEL|FALTA|EXCESSO/.test(text)) return "danger";
  if (/RASCUNHO|PENDENTE/.test(text)) return "neutral";
  return "warning";
}

function Status({ value }) {
  return h("span", { className: `ent-status ${statusTone(value)}` }, humanStatus(value));
}

function Empty({ children }) {
  return h("div", { className: "ent-empty" },
    h("span", { "aria-hidden": "true" }, "◇"), h("p", null, children));
}

function Panel({ title, subtitle, action, children, className = "" }) {
  return h("section", { className: `ent-panel ${className}` },
    h("header", { className: "ent-panel-head" },
      h("div", null, h("h3", null, title), subtitle && h("p", null, subtitle)), action),
    children
  );
}

function Field({ label, children, hint }) {
  return h("label", { className: "ent-field" }, h("span", null, label), children,
    hint && h("small", null, hint));
}

function Metric({ label, value, detail, tone = "default" }) {
  return h("article", { className: `ent-metric ${tone}` },
    h("p", null, label), h("strong", null, value), detail && h("span", null, detail));
}

function DataTable({ columns, rows, emptyText = "Nenhum registro encontrado.", onRow }) {
  if (!rows?.length) return h(Empty, null, emptyText);
  return h("div", { className: "ent-table-wrap" },
    h("table", { className: "ent-table" },
      h("thead", null, h("tr", null, columns.map((column) => h("th", { key: column.key }, column.label)))),
      h("tbody", null, rows.map((row, index) => h("tr", {
        key: row.id || row.produto_id || index,
        className: onRow ? "clickable" : "",
        onClick: onRow ? () => onRow(row) : undefined
      }, columns.map((column) => h("td", { key: column.key },
        column.render ? column.render(row) : row[column.key] ?? "—"
      )))))
    )
  );
}

export function EnterpriseWorkspace({ view, user, legacyCounts = [], legacyUsers = [], notify = () => {} }) {
  const [workspace, setWorkspace] = React.useState(null);
  const [branchId, setBranchId] = React.useState("");
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState("");
  const [queueSize, setQueueSize] = React.useState(readQueue().length);
  const [globalSearch, setGlobalSearch] = React.useState("");
  const [searchResults, setSearchResults] = React.useState([]);

  const load = React.useCallback(async (selectedBranch = branchId) => {
    setLoading(true);
    setError("");
    try {
      const query = selectedBranch ? `?branchId=${encodeURIComponent(selectedBranch)}` : "";
      const body = await enterpriseJson(`/api/v2/workspace${query}`);
      setWorkspace(body);
      if (!selectedBranch && body.branchId) setBranchId(body.branchId);
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      setLoading(false);
    }
  }, [branchId]);

  React.useEffect(() => { load(); }, [view]);
  React.useEffect(() => {
    const synchronize = async () => {
      const completed = await syncQueue();
      setQueueSize(readQueue().length);
      if (completed) {
        notify(`${completed} operação(ões) off-line sincronizada(s).`);
        load();
      }
    };
    window.addEventListener("online", synchronize);
    synchronize();
    return () => window.removeEventListener("online", synchronize);
  }, []);

  async function run(action, success) {
    setError("");
    try {
      const result = await action();
      if (result?.rejected) throw new Error(result.error || "Leitura recusada e registrada como divergência.");
      if (result?.queued) {
        setQueueSize(readQueue().length);
        notify("Operação salva no aparelho e aguardando conexão.");
      } else {
        notify(success);
        await load();
      }
      return result;
    } catch (actionError) {
      await load();
      setError(actionError.message);
      return null;
    }
  }

  async function search(event) {
    event.preventDefault();
    if (globalSearch.trim().length < 2) return;
    try {
      const response = await enterpriseJson(`/api/v2/search?branchId=${encodeURIComponent(branchId)}&q=${encodeURIComponent(globalSearch.trim())}`);
      setSearchResults(response.results || []);
    } catch (searchError) { setError(searchError.message); }
  }

  if (loading && !workspace) return h("div", { className: "ent-loading" }, h("span", null), "Carregando operação...");
  if (!workspace) return h(Panel, { title: "Módulo empresarial indisponível" },
    h("div", { className: "ent-error" }, error || "Não foi possível carregar os dados.",
      h("button", { className: "secondary-action compact", onClick: () => load() }, "Tentar novamente")));

  const common = { workspace, user, branchId, run, load, legacyCounts, legacyUsers };
  const pages = {
    overview: OverviewPage,
    receiving: ReceivingPage,
    inventory: InventoryPage,
    separation: PickingPage,
    conference: ExpeditionPage,
    transfers: TransfersPage,
    exceptions: ExceptionsPage,
    audit: AuditPage,
    catalog: CatalogPage
  };
  const Page = pages[view] || OverviewPage;

  return h("div", { className: "enterprise-shell" },
    h("div", { className: "ent-contextbar" },
      h("div", null,
        h("span", { className: "ent-live-dot" }),
        h("strong", null, "Operação empresarial"),
        h("small", null, queueSize ? `${queueSize} pendente(s) de sincronização` : "dados sincronizados")
      ),
      h("form", { className: "ent-global-search", onSubmit: search },
        h("input", { value: globalSearch, onChange: (event) => { setGlobalSearch(event.target.value); if (!event.target.value) setSearchResults([]); }, placeholder: "Buscar produto, NF-e, mapa ou transferência", "aria-label": "Busca global" }),
        h("button", { type: "submit", "aria-label": "Pesquisar" }, "⌕"),
        searchResults.length > 0 && h("div", { className: "ent-search-results" }, searchResults.map((result, index) =>
          h("div", { key: `${result.tipo}-${result.id}-${index}` }, h(Status, { value: result.tipo }),
            h("span", null, h("strong", null, result.titulo), h("small", null, result.subtitulo)))))
      ),
      user.role === "admin" && h("select", {
        value: branchId,
        onChange: (event) => { setBranchId(event.target.value); load(event.target.value); },
        "aria-label": "Filial em exibição"
      },
        h("option", { value: "all" }, "Todas as filiais"),
        workspace.branches.map((branch) => h("option", { key: branch.id, value: branch.id }, branch.nome))
      ),
      h("button", { className: "ent-icon-button", title: "Atualizar dados", onClick: () => load() }, "↻")
    ),
    error && h("div", { className: "ent-alert danger", role: "alert" },
      h("strong", null, "A operação não foi concluída"), h("span", null, error),
      h("button", { onClick: () => setError("") }, "×")),
    h(Page, common)
  );
}

function OverviewPage({ workspace }) {
  const stock = workspace.dashboard?.stock || {};
  const tasks = [
    ["Recebimentos aguardando", workspace.dashboard?.pendingReceipts || 0, "receiving"],
    ["Mapas em operação", workspace.dashboard?.pendingMaps || 0, "warning"],
    ["Divergências abertas", workspace.dashboard?.openExceptions || 0, "danger"],
    ["Transferências em trânsito", workspace.transfers.filter((item) => item.status === "EM_TRANSITO").length, "default"]
  ];
  return h("div", { className: "ent-page" },
    h("section", { className: "ent-hero" },
      h("div", null, h("p", null, "Visão operacional"), h("h2", null, "O que precisa de atenção agora"),
        h("span", null, "Dados consolidados do recebimento à expedição.")),
      h("div", { className: "ent-hero-badge" }, h("strong", null, number(stock.skus)), h("span", null, "SKUs com saldo"))
    ),
    h("div", { className: "ent-metric-grid" },
      h(Metric, { label: "Estoque físico", value: number(stock.fisico), detail: "unidades sob custódia" }),
      h(Metric, { label: "Disponível", value: number(stock.disponivel), detail: "livre para reservar", tone: "success" }),
      h(Metric, { label: "Reservado", value: number(stock.reservado), detail: "em separação", tone: "warning" }),
      h(Metric, { label: "Quarentena", value: number(stock.quarentena), detail: "aguardando decisão", tone: "danger" })
    ),
    h("div", { className: "ent-two-columns" },
      h(Panel, { title: "Fila de trabalho", subtitle: "Prioridades atualizadas em tempo real" },
        h("div", { className: "ent-task-list" }, tasks.map(([label, amount, tone]) =>
          h("div", { key: label }, h("span", { className: `ent-task-mark ${tone}` }),
            h("strong", null, label), h("b", null, number(amount))))),
      ),
      h(Panel, { title: "Movimentos recentes", subtitle: "Últimos lançamentos do livro de estoque" },
        h("div", { className: "ent-timeline compact" }, workspace.movements.slice(0, 6).map((movement) =>
          h("div", { key: movement.id },
            h("span", { className: `ent-timeline-dot ${statusTone(movement.tipo)}` }),
            h("div", null, h("strong", null, movement.tipo.replaceAll("_", " ")),
              h("small", null, `${movement.codigo_interno} · ${movement.criado_por}`)),
            h("time", null, date(movement.ocorrido_em)))) || h(Empty, null, "Nenhum movimento publicado."))
      )
    )
  );
}

function InventoryPage({ workspace, user, branchId, run, legacyCounts }) {
  const [search, setSearch] = React.useState("");
  const rows = workspace.balances.filter((item) =>
    `${item.sku} ${item.codigo_interno} ${item.descricao}`.toLowerCase().includes(search.toLowerCase()));
  async function publishCount(opening) {
    if (!legacyCounts.length) return;
    const reason = opening ? "Saldo inicial por contagem física" : window.prompt("Informe o motivo do ajuste:");
    if (!reason) return;
    await run(() => mutate("/api/v2/counts/apply", {
      branchId, opening, countReference: `contagem-${Date.now()}`, reason,
      items: legacyCounts.map((item) => ({ code: item.sku, quantity: Number(item.counted || 0) }))
    }, opening ? "opening" : "count-adjustment"), opening ? "Saldo inicial publicado." : "Ajustes publicados.");
  }
  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" },
      h("div", null, h("h2", null, "Posição de estoque"), h("p", null, "Saldo projetado a partir do livro imutável de movimentos.")),
      ["admin", "supervisor"].includes(user.role) && h("div", { className: "ent-action-row" },
        h("button", { className: "secondary-action compact", disabled: !legacyCounts.length, onClick: () => publishCount(false) }, "Aplicar ajuste aprovado"),
        h("button", { className: "primary-action compact", disabled: !legacyCounts.length, onClick: () => publishCount(true) }, "Publicar saldo inicial"))
    ),
    h(Panel, { title: "Saldos por produto", subtitle: `${rows.length} produto(s) no filtro`,
      action: h("input", { className: "ent-search", value: search, onChange: (e) => setSearch(e.target.value), placeholder: "Buscar SKU, código ou descrição" }) },
      h(DataTable, { rows, columns: [
        { key: "codigo_interno", label: "Código interno" }, { key: "sku", label: "SKU" },
        { key: "descricao", label: "Produto" },
        { key: "fisico", label: "Físico", render: (row) => h("strong", null, number(row.fisico)) },
        { key: "disponivel", label: "Disponível", render: (row) => number(row.disponivel) },
        { key: "reservado", label: "Reservado", render: (row) => number(row.reservado) },
        { key: "em_transito", label: "Em trânsito", render: (row) => number(row.em_transito) },
        { key: "quarentena", label: "Quarentena", render: (row) => number(row.quarentena) }
      ] })
    )
  );
}

function ReceivingPage({ workspace, branchId, run }) {
  const [formOpen, setFormOpen] = React.useState(false);
  const [draft, setDraft] = React.useState({ reference: "", supplier: "" });
  const [selected, setSelected] = React.useState(null);
  const [scan, setScan] = React.useState({ code: "", quantity: 1, condition: "REGULAR", reason: "" });
  const [printerId, setPrinterId] = React.useState("");
  const fileRef = React.useRef(null);
  const receiptPrinters = (workspace.printers || []).filter((printer) =>
    !selected || printer.filial_id === selected.filial_id);

  async function openReceipt(row) {
    try { setSelected(await enterpriseJson(`/api/v2/receipts/${row.id}`)); }
    catch (error) { window.alert(error.message); }
  }

  async function create(event) {
    event.preventDefault();
    const result = await run(() => mutate("/api/v2/receipts", { ...draft, branchId }, "create-receipt"),
      "Recebimento criado.");
    if (result) { setSelected(result); setDraft({ reference: "", supplier: "" }); setFormOpen(false); }
  }

  async function importXml(event) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !selected) return;
    const content = await fileAsDataUrl(file);
    const result = await run(() => mutate(`/api/v2/receipts/${selected.id}/nfe`,
      { fileName: file.name, content }, "import-nfe"), "NF-e adicionada à carga.");
    if (result) setSelected(result);
  }

  async function scanItem(event) {
    event.preventDefault();
    if (!selected) return;
    const damaged = scan.condition === "DAMAGED";
    const path = damaged ? "damage" : "scan";
    const result = await run(() => mutate(`/api/v2/receipts/${selected.id}/${path}`, {
      code: scan.code, quantity: scan.quantity, reason: scan.reason, deviceId: deviceId()
    }, damaged ? "receipt-damage" : "receipt-scan", true),
    damaged ? "Avaria registrada em quarentena." : "Leitura confirmada.");
    if (result && !result.queued) setSelected(result);
    setScan({ code: "", quantity: 1, condition: "REGULAR", reason: "" });
  }

  async function finalize() {
    if (!window.confirm("Finalizar o recebimento e publicar as entradas confirmadas no estoque?")) return;
    const result = await run(() => mutate(`/api/v2/receipts/${selected.id}/finalize`, {}, "receipt-finalize"),
      "Recebimento finalizado e estoque atualizado.");
    if (result) setSelected(result);
  }

  async function printLabel(item, reprint = false) {
    const reason = reprint ? window.prompt("Motivo da reimpressão:") : "";
    if (reprint && !reason) return;
    const labels = Number(window.prompt("Quantas etiquetas imprimir?", "1") || 0);
    if (labels <= 0) return;
    const job = await run(() => mutate("/api/v2/print-jobs", {
        branchId: selected.filial_id, printerId: printerId || receiptPrinters[0]?.id || "",
        productId: item.produto_id, originId: selected.id, originType: "RECEBIMENTO",
        labels, reprint, reason, widthMm: 60, heightMm: 40, dpi: 203
      }, "print-label"), reprint ? "Reimpressão registrada." : "Impressão registrada.");
    if (job) renderLabel(job);
  }

  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" },
      h("div", null, h("h2", null, "Recebimentos"), h("p", null, "Confirme a descarga antes de qualquer entrada no saldo.")),
      h("button", { className: "primary-action compact", onClick: () => setFormOpen((value) => !value) },
        formOpen ? "Fechar cadastro" : "Nova carga")
    ),
    formOpen && h(Panel, { title: "Abrir carga de recebimento", subtitle: "Uma carga pode reunir várias NF-e" },
      h("form", { className: "ent-inline-form", onSubmit: create },
        h(Field, { label: "Referência da carga" }, h("input", { required: true, value: draft.reference,
          onChange: (e) => setDraft({ ...draft, reference: e.target.value }), placeholder: "Ex.: CARGA-2026-0818" })),
        h(Field, { label: "Fornecedor (opcional)" }, h("input", { value: draft.supplier,
          onChange: (e) => setDraft({ ...draft, supplier: e.target.value }), placeholder: "Será preenchido pela NF-e" })),
        h("button", { className: "primary-action", type: "submit" }, "Criar recebimento")
      )
    ),
    h("div", { className: selected ? "ent-master-detail" : "" },
      h(Panel, { title: "Cargas", subtitle: `${workspace.receipts.length} recebimento(s)` },
        h(DataTable, { rows: workspace.receipts, onRow: openReceipt, columns: [
          { key: "referencia", label: "Referência", render: (row) => h("strong", null, row.referencia) },
          { key: "fornecedor", label: "Fornecedor" },
          { key: "status", label: "Status", render: (row) => h(Status, { value: row.status }) },
          { key: "progress", label: "Progresso", render: (row) => `${number(row.recebido)}/${number(row.esperado)}` },
          { key: "criado_em", label: "Criado em", render: (row) => date(row.criado_em) }
        ] })
      ),
      selected && h("aside", { className: "ent-detail" },
        h("header", null,
          h("div", null, h("small", null, "Carga"), h("h3", null, selected.referencia), h(Status, { value: selected.status })),
          h("button", { className: "ent-icon-button", onClick: () => setSelected(null), "aria-label": "Fechar detalhes" }, "×")
        ),
        h("div", { className: "ent-detail-summary" },
          h("div", null, h("span", null, "NF-e"), h("strong", null, selected.documents?.length || 0)),
          h("div", null, h("span", null, "Esperado"), h("strong", null, number(sum(selected.items, "quantidade_esperada")))),
          h("div", null, h("span", null, "Recebido"), h("strong", null,
            number(sum(selected.items, "quantidade_recebida") + sum(selected.items, "quantidade_avariada"))))
        ),
        ["RASCUNHO", "EM_RECEBIMENTO"].includes(selected.status) && h(React.Fragment, null,
          h("input", { className: "hidden", ref: fileRef, type: "file", accept: ".xml,application/xml,text/xml", onChange: importXml }),
          h("button", { className: "secondary-action ent-full", onClick: () => fileRef.current?.click() }, "Adicionar XML de NF-e"),
          selected.status === "EM_RECEBIMENTO" && h("form", { className: "ent-scan-box", onSubmit: scanItem },
            h("div", null, h("span", null, "Leitura da descarga"), h("strong", null, "EAN, SKU ou código interno"),
              h("select", { className: "ent-scan-mode", value: scan.condition, onChange: (e) => setScan({ ...scan, condition: e.target.value, reason: "" }) },
                h("option", { value: "REGULAR" }, "Recebimento regular"), h("option", { value: "DAMAGED" }, "Produto avariado"))),
            h("div", { className: scan.condition === "DAMAGED" ? "damage" : "" },
              h("input", { autoFocus: true, required: true, value: scan.code, onChange: (e) => setScan({ ...scan, code: e.target.value }), placeholder: "Bipe ou digite o código" }),
              h("input", { type: "number", min: 1, value: scan.quantity, onChange: (e) => setScan({ ...scan, quantity: Number(e.target.value) }) }),
              scan.condition === "DAMAGED" && h("input", { required: true, value: scan.reason, onChange: (e) => setScan({ ...scan, reason: e.target.value }), placeholder: "Motivo da avaria" }),
              h("button", { className: "primary-action", type: "submit" }, scan.condition === "DAMAGED" ? "Registrar avaria" : "Confirmar")))
        ),
        receiptPrinters.length > 0 && h(Field, { label: "Impressora de etiquetas" },
          h("select", { value: printerId || receiptPrinters[0]?.id, onChange: (e) => setPrinterId(e.target.value) },
            receiptPrinters.map((printer) => h("option", { key: printer.id, value: printer.id },
              `${printer.nome} · ${printer.fabricante} ${printer.largura_mm}×${printer.altura_mm} mm`)))),
        h("div", { className: "ent-item-list" }, (selected.items || []).map((item) =>
          h("article", { key: item.id },
            h("div", null, h("strong", null, item.produto_codigo || item.codigo_interno || item.sku_informado),
              h("span", null, item.produto_descricao || item.descricao), h(Status, { value: item.status })),
            h("div", { className: "ent-quantity" },
              h("span", null, `${number(Number(item.quantidade_recebida || 0) + Number(item.quantidade_avariada || 0))}/${number(item.quantidade_esperada)}`),
              Number(item.quantidade_avariada || 0) > 0 && h("small", null, `${number(item.quantidade_avariada)} avariada(s)`),
              item.produto_id && h("button", { className: "ent-text-action", onClick: () => printLabel(item) }, "Etiqueta"))))
        ),
        selected.status === "EM_RECEBIMENTO" && h("button", { className: "primary-action ent-full", onClick: finalize }, "Finalizar recebimento")
      )
    )
  );
}

function PickingPage({ workspace, branchId, user, run }) {
  const [creating, setCreating] = React.useState(false);
  const [draft, setDraft] = React.useState({ mapNumber: "", orders: "", customer: "", route: "", fileName: "", fileHash: "", itemsText: "" });
  const [selected, setSelected] = React.useState(null);
  const [scan, setScan] = React.useState({ code: "", quantity: 1 });
  const [analyzing, setAnalyzing] = React.useState(false);
  const [file, setFile] = React.useState(null);

  async function openMap(row) {
    try { setSelected(await enterpriseJson(`/api/v2/maps/${row.id}`)); }
    catch (error) { window.alert(error.message); }
  }

  async function analyzePdf() {
    if (!file || !draft.mapNumber || !draft.orders.trim()) return;
    setAnalyzing(true);
    try {
      const response = await enterpriseJson("/api/maps/analyze", {
        method: "POST",
        body: {
          mapNumber: draft.mapNumber,
          orderNumbers: draft.orders.split(/[,;\s]+/).filter(Boolean),
          files: [{ fileName: file.name, contentType: file.type || "application/pdf", dataUrl: await fileAsDataUrl(file) }]
        }
      });
      const source = response.draft || {};
      const itemsText = (source.items || []).map((item) => `${item.sku || item.barcode || ""},${item.quantity || 0}`).join("\n");
      const fileHash = await fileSha256(file);
      setDraft((current) => ({ ...current, customer: source.client || current.customer,
        route: source.route || current.route, fileName: file.name, fileHash, itemsText }));
    } catch (error) {
      window.alert(error.message);
    } finally { setAnalyzing(false); }
  }

  async function create(event) {
    event.preventDefault();
    const items = parseItemLines(draft.itemsText);
    const result = await run(() => mutate("/api/v2/maps", { ...draft, branchId, items }, "create-map"),
      "Mapa revisado salvo como rascunho.");
    if (result) {
      setSelected(result); setCreating(false); setFile(null);
      setDraft({ mapNumber: "", orders: "", customer: "", route: "", fileName: "", fileHash: "", itemsText: "" });
    }
  }

  async function publish() {
    if (!window.confirm("Publicar o mapa e reservar o estoque disponível?")) return;
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/publish`, {}, "map-publish"), "Mapa publicado e saldo reservado.");
    if (result) setSelected(result);
  }

  async function scanItem(event) {
    event.preventDefault();
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/scan-picking`, {
      ...scan, deviceId: deviceId()
    }, "picking-scan", true), "Produto separado.");
    if (result && !result.queued) setSelected(result);
    setScan({ code: "", quantity: 1 });
  }

  async function finish() {
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/finish-picking`, {}, "finish-picking"),
      "Separação finalizada e enviada para reconferência.");
    if (result) setSelected(result);
  }

  async function cancel() {
    const reason = window.prompt("Informe o motivo do cancelamento:");
    if (!reason) return;
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/cancel`, { reason }, "map-cancel"),
      "Mapa cancelado e reservas liberadas.");
    if (result) setSelected(result);
  }
  async function authorizeShortage() {
    const reason = window.prompt("Justificativa obrigatória para autorizar a falta na separação:");
    if (!reason) return;
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/authorize-shortage`,
      { stage: "PICKING", reason }, "authorize-picking-shortage"), "Falta autorizada e reserva liberada.");
    if (result) setSelected(result);
  }
  const pickingShortage = (selected?.items || []).some((item) =>
    Number(item.quantidade_separada || 0) < effectiveExpected(item));

  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" },
      h("div", null, h("h2", null, "Separação"), h("p", null, "O estoque é reservado na publicação e permanece físico até a expedição.")),
      h("button", { className: "primary-action compact", onClick: () => setCreating((value) => !value) },
        creating ? "Fechar" : "Novo mapa")
    ),
    creating && h(Panel, { title: "Revisar mapa de separação", subtitle: "A extração do PDF nunca publica o mapa automaticamente" },
      h("form", { className: "ent-form-grid", onSubmit: create },
        h(Field, { label: "Número do mapa" }, h("input", { required: true, value: draft.mapNumber, onChange: (e) => setDraft({ ...draft, mapNumber: e.target.value }) })),
        h(Field, { label: "Pedidos" }, h("input", { required: true, value: draft.orders, onChange: (e) => setDraft({ ...draft, orders: e.target.value }), placeholder: "Separe por vírgulas" })),
        h(Field, { label: "Cliente" }, h("input", { value: draft.customer, onChange: (e) => setDraft({ ...draft, customer: e.target.value }) })),
        h(Field, { label: "Rota" }, h("input", { value: draft.route, onChange: (e) => setDraft({ ...draft, route: e.target.value }) })),
        h(Field, { label: "PDF oficial", hint: "Use a leitura automática e revise os itens abaixo." },
          h("div", { className: "ent-file-row" },
            h("input", { type: "file", accept: ".pdf,application/pdf", onChange: (e) => {
              setFile(e.target.files?.[0] || null);
              setDraft((current) => ({ ...current, fileName: "", fileHash: "" }));
            } }),
            h("button", { type: "button", className: "secondary-action compact", disabled: !file || analyzing, onClick: analyzePdf }, analyzing ? "Analisando..." : "Extrair PDF"))),
        h(Field, { label: "Itens revisados", hint: "Uma linha por produto no formato CODIGO,QUANTIDADE" },
          h("textarea", { required: true, rows: 7, value: draft.itemsText, onChange: (e) => setDraft({ ...draft, itemsText: e.target.value }), placeholder: "12345.1.1,10\n789012,4" })),
        h("div", { className: "ent-form-footer" }, h("span", null, "Confira todos os itens antes de continuar."),
          h("button", { className: "primary-action", type: "submit", disabled: !draft.fileHash }, "Salvar mapa revisado"))
      )
    ),
    h("div", { className: selected ? "ent-master-detail" : "" },
      h(Panel, { title: "Mapas", subtitle: `${workspace.maps.length} mapa(s)` },
        h(DataTable, { rows: workspace.maps, onRow: openMap, columns: [
          { key: "numero_mapa", label: "Mapa", render: (row) => h("strong", null, row.numero_mapa) },
          { key: "cliente", label: "Cliente" }, { key: "rota", label: "Rota" },
          { key: "status", label: "Status", render: (row) => h(Status, { value: row.status }) },
          { key: "progress", label: "Separado", render: (row) => `${number(row.separado)}/${number(row.esperado)}` }
        ] })
      ),
      selected && h("aside", { className: "ent-detail" },
        h("header", null, h("div", null, h("small", null, "Mapa"), h("h3", null, selected.numero_mapa), h(Status, { value: selected.status })),
          h("button", { className: "ent-icon-button", onClick: () => setSelected(null) }, "×")),
        h("p", { className: "ent-detail-copy" }, `${selected.cliente || "Cliente não informado"} · ${selected.rota || "Sem rota"}`),
        selected.status === "RASCUNHO" && h("button", { className: "primary-action ent-full", onClick: publish }, "Publicar e reservar estoque"),
        selected.status === "PUBLICADO" && h("form", { className: "ent-scan-box", onSubmit: scanItem },
          h("div", null, h("span", null, "Confirmar separação"), h("strong", null, "Código interno, SKU ou EAN")),
          h("div", null,
            h("input", { autoFocus: true, required: true, value: scan.code, onChange: (e) => setScan({ ...scan, code: e.target.value }), placeholder: "Bipe o código interno" }),
            h("input", { type: "number", min: 1, value: scan.quantity, onChange: (e) => setScan({ ...scan, quantity: Number(e.target.value) }) }),
            h("button", { className: "primary-action", type: "submit" }, "Confirmar"))),
        h("div", { className: "ent-item-list" }, (selected.items || []).map((item) =>
          h("article", { key: item.id },
            h("div", null, h("strong", null, item.codigo_interno), h("span", null, item.descricao), h(Status, { value: item.status })),
            h("div", { className: "ent-quantity" }, `${number(item.quantidade_separada)}/${number(effectiveExpected(item))}`,
              Number(item.quantidade_cancelada || 0) > 0 && h("small", null, `${number(item.quantidade_cancelada)} autorizada(s)`))))),
        selected.status === "PUBLICADO" && pickingShortage && ["admin", "supervisor"].includes(user.role) &&
          h("button", { className: "secondary-action ent-full", onClick: authorizeShortage }, "Autorizar falta da separação"),
        selected.status === "PUBLICADO" && h("button", { className: "primary-action ent-full", onClick: finish, disabled: pickingShortage },
          pickingShortage ? "Separação com itens pendentes" : "Finalizar separação"),
        ["RASCUNHO", "PUBLICADO", "AGUARDANDO_CONFERENCIA"].includes(selected.status) &&
          h("button", { className: "ent-text-action danger", onClick: cancel }, "Cancelar mapa")
      )
    )
  );
}

function ExpeditionPage({ workspace, user, run }) {
  const [selected, setSelected] = React.useState(null);
  const [scan, setScan] = React.useState({ code: "", quantity: 1 });
  const maps = workspace.maps.filter((item) => ["AGUARDANDO_CONFERENCIA", "EXPEDIDO"].includes(item.status));
  async function openMap(row) {
    try { setSelected(await enterpriseJson(`/api/v2/maps/${row.id}`)); }
    catch (error) { window.alert(error.message); }
  }
  async function scanItem(event) {
    event.preventDefault();
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/scan-conference`, {
      ...scan, deviceId: deviceId()
    }, "conference-scan", true), "Item reconferido.");
    if (result && !result.queued) setSelected(result);
    setScan({ code: "", quantity: 1 });
  }
  async function dispatch() {
    if (!window.confirm("Confirmar a expedição? O saldo físico será baixado.")) return;
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/dispatch`, {}, "dispatch"),
      "Expedição finalizada e saldo baixado.");
    if (result) setSelected(result);
  }
  async function authorizeShortage() {
    const reason = window.prompt("Justificativa obrigatória para autorizar a falta na reconferência:");
    if (!reason) return;
    const result = await run(() => mutate(`/api/v2/maps/${selected.id}/authorize-shortage`,
      { stage: "CONFERENCE", reason }, "authorize-conference-shortage"),
      "Falta da reconferência autorizada e reserva liberada.");
    if (result) setSelected(result);
  }
  const conferenceShortage = (selected?.items || []).some((item) =>
    Number(item.quantidade_conferida || 0) < effectiveExpected(item));
  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" }, h("div", null,
      h("h2", null, "Expedição"), h("p", null, "Reconferência independente antes da baixa física."))),
    h("div", { className: selected ? "ent-master-detail" : "" },
      h(Panel, { title: "Fila de reconferência", subtitle: `${maps.filter((item) => item.status !== "EXPEDIDO").length} aguardando` },
        h(DataTable, { rows: maps, onRow: openMap, columns: [
          { key: "numero_mapa", label: "Mapa", render: (row) => h("strong", null, row.numero_mapa) },
          { key: "cliente", label: "Cliente" }, { key: "rota", label: "Rota" },
          { key: "status", label: "Status", render: (row) => h(Status, { value: row.status }) },
          { key: "progress", label: "Conferido", render: (row) => `${number(row.conferido)}/${number(row.esperado)}` }
        ] })
      ),
      selected && h("aside", { className: "ent-detail" },
        h("header", null, h("div", null, h("small", null, "Reconferência"), h("h3", null, selected.numero_mapa), h(Status, { value: selected.status })),
          h("button", { className: "ent-icon-button", onClick: () => setSelected(null) }, "×")),
        selected.status === "AGUARDANDO_CONFERENCIA" && h("form", { className: "ent-scan-box", onSubmit: scanItem },
          h("div", null, h("span", null, "Leitura independente"), h("strong", null, "Bipe cada produto novamente")),
          h("div", null,
            h("input", { autoFocus: true, required: true, value: scan.code, onChange: (e) => setScan({ ...scan, code: e.target.value }), placeholder: "Código interno, SKU ou EAN" }),
            h("input", { type: "number", min: 1, value: scan.quantity, onChange: (e) => setScan({ ...scan, quantity: Number(e.target.value) }) }),
            h("button", { className: "primary-action", type: "submit" }, "Conferir"))),
        h("div", { className: "ent-item-list" }, (selected.items || []).map((item) =>
          h("article", { key: item.id }, h("div", null, h("strong", null, item.codigo_interno), h("span", null, item.descricao)),
            h("div", { className: "ent-quantity" }, `${number(item.quantidade_conferida)}/${number(effectiveExpected(item))}`,
              Number(item.quantidade_cancelada || 0) > 0 && h("small", null, `${number(item.quantidade_cancelada)} autorizada(s)`))))),
        selected.status === "AGUARDANDO_CONFERENCIA" && conferenceShortage && ["admin", "supervisor"].includes(user.role) &&
          h("button", { className: "secondary-action ent-full", onClick: authorizeShortage }, "Autorizar falta da reconferência"),
        selected.status === "AGUARDANDO_CONFERENCIA" && h("button", { className: "primary-action ent-full", onClick: dispatch, disabled: conferenceShortage },
          conferenceShortage ? "Reconferência com itens pendentes" : "Finalizar expedição")
      )
    )
  );
}

function TransfersPage({ workspace, branchId, user, run }) {
  const [creating, setCreating] = React.useState(false);
  const [draft, setDraft] = React.useState({ reference: "", destinationBranchId: "", vehicle: "", route: "", itemsText: "" });
  const [selected, setSelected] = React.useState(null);
  const [receiveScan, setReceiveScan] = React.useState({ code: "", quantity: 1 });
  const [receivedCounts, setReceivedCounts] = React.useState({});
  async function open(row) {
    try { setSelected(await enterpriseJson(`/api/v2/transfers/${row.id}`)); setReceivedCounts({}); }
    catch (error) { window.alert(error.message); }
  }
  async function create(event) {
    event.preventDefault();
    const result = await run(() => mutate("/api/v2/transfers", {
      ...draft, originBranchId: branchId, items: parseItemLines(draft.itemsText)
    }, "create-transfer"), "Transferência criada.");
    if (result) { setSelected(result); setCreating(false); }
  }
  async function action(name, message) {
    const result = await run(() => mutate(`/api/v2/transfers/${selected.id}/${name}`, {}, `transfer-${name}`), message);
    if (result) setSelected(result);
  }
  async function receive() {
    const items = selected.items.map((item) => ({ itemId: item.id, quantity: receivedCounts[item.id] || 0 }));
    const divergent = items.some((entry, index) => entry.quantity !== Number(selected.items[index].quantidade));
    const message = divergent
      ? "Há falta ou excesso na reconferência. Finalizar e encaminhar a divergência para análise?"
      : "Confirmar o recebimento integral desta transferência?";
    if (!window.confirm(message)) return;
    const result = await run(() => mutate(`/api/v2/transfers/${selected.id}/receive`, { items }, "transfer-receive"),
      "Transferência reconferida e recebida.");
    if (result) setSelected(result);
  }
  function scanTransfer(event) {
    event.preventDefault();
    const code = receiveScan.code.trim();
    const item = selected.items.find((candidate) => [candidate.codigo_interno, candidate.sku,
      ...(candidate.eans || "").split(",")].map(String).includes(code));
    if (!item) {
      window.alert("Produto estranho à transferência. A leitura não foi aceita.");
      return;
    }
    setReceivedCounts((current) => ({ ...current,
      [item.id]: (current[item.id] || 0) + Number(receiveScan.quantity || 0) }));
    setReceiveScan({ code: "", quantity: 1 });
  }
  async function cancel() {
    const reason = window.prompt("Informe o motivo do cancelamento:");
    if (!reason) return;
    const result = await run(() => mutate(`/api/v2/transfers/${selected.id}/cancel`, { reason }, "transfer-cancel"),
      "Transferência cancelada e reserva liberada.");
    if (result) setSelected(result);
  }
  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" },
      h("div", null, h("h2", null, "Transferências"), h("p", null, "Origem, trânsito e destino conciliados no mesmo fluxo.")),
      h("button", { className: "primary-action compact", onClick: () => setCreating((value) => !value) }, creating ? "Fechar" : "Nova transferência")),
    creating && h(Panel, { title: "Solicitar transferência" },
      h("form", { className: "ent-form-grid", onSubmit: create },
        h(Field, { label: "Referência" }, h("input", { required: true, value: draft.reference, onChange: (e) => setDraft({ ...draft, reference: e.target.value }) })),
        h(Field, { label: "Filial de destino" }, h("select", { required: true, value: draft.destinationBranchId, onChange: (e) => setDraft({ ...draft, destinationBranchId: e.target.value }) },
          h("option", { value: "" }, "Selecione"), workspace.branches.filter((branch) => branch.id !== branchId).map((branch) => h("option", { key: branch.id, value: branch.id }, branch.nome)))),
        h(Field, { label: "Veículo" }, h("input", { value: draft.vehicle, onChange: (e) => setDraft({ ...draft, vehicle: e.target.value }) })),
        h(Field, { label: "Rota" }, h("input", { value: draft.route, onChange: (e) => setDraft({ ...draft, route: e.target.value }) })),
        h(Field, { label: "Itens", hint: "CODIGO,QUANTIDADE — uma linha por produto" }, h("textarea", { required: true, rows: 6, value: draft.itemsText, onChange: (e) => setDraft({ ...draft, itemsText: e.target.value }) })),
        h("div", { className: "ent-form-footer" }, h("span"), h("button", { className: "primary-action", type: "submit" }, "Criar solicitação"))
      )
    ),
    h("div", { className: selected ? "ent-master-detail" : "" },
      h(Panel, { title: "Operações entre filiais", subtitle: `${workspace.transfers.length} transferência(s)` },
        h(DataTable, { rows: workspace.transfers, onRow: open, columns: [
          { key: "referencia", label: "Referência", render: (row) => h("strong", null, row.referencia) },
          { key: "route", label: "Fluxo", render: (row) => `${row.filial_origem} → ${row.filial_destino}` },
          { key: "status", label: "Status", render: (row) => h(Status, { value: row.status }) },
          { key: "quantidade", label: "Unidades", render: (row) => number(row.quantidade) },
          { key: "criado_em", label: "Criada em", render: (row) => date(row.criado_em) }
        ] })
      ),
      selected && h("aside", { className: "ent-detail" },
        h("header", null, h("div", null, h("small", null, "Transferência"), h("h3", null, selected.referencia), h(Status, { value: selected.status })),
          h("button", { className: "ent-icon-button", onClick: () => setSelected(null) }, "×")),
        h("p", { className: "ent-route" }, h("strong", null, selected.filial_origem), h("span", null, "→"), h("strong", null, selected.filial_destino)),
        selected.status === "EM_TRANSITO" && h("form", { className: "ent-scan-box", onSubmit: scanTransfer },
          h("div", null, h("span", null, "Reconferência no destino"), h("strong", null, "Bipe cada produto recebido")),
          h("div", null,
            h("input", { autoFocus: true, required: true, value: receiveScan.code, onChange: (e) => setReceiveScan({ ...receiveScan, code: e.target.value }), placeholder: "Código interno, SKU ou EAN" }),
            h("input", { type: "number", min: 1, value: receiveScan.quantity, onChange: (e) => setReceiveScan({ ...receiveScan, quantity: Number(e.target.value) }) }),
            h("button", { className: "primary-action", type: "submit" }, "Conferir"))),
        h("div", { className: "ent-item-list" }, selected.items.map((item) =>
          h("article", { key: item.id }, h("div", null, h("strong", null, item.codigo_interno), h("span", null, item.descricao)),
            h("div", { className: "ent-quantity" }, selected.status === "EM_TRANSITO"
              ? `${number(receivedCounts[item.id] || 0)}/${number(item.quantidade)}` : number(item.quantidade))))),
        selected.status === "RASCUNHO" && ["admin", "supervisor"].includes(user.role) &&
          h("button", { className: "primary-action ent-full", onClick: () => action("approve", "Transferência aprovada e estoque reservado.") }, "Aprovar e reservar"),
        selected.status === "APROVADA" && h("button", { className: "primary-action ent-full", onClick: () => action("ship", "Transferência expedida e colocada em trânsito.") }, "Expedir transferência"),
        selected.status === "EM_TRANSITO" && h("button", { className: "primary-action ent-full", onClick: receive }, "Reconferir e receber no destino")
        , ["RASCUNHO", "APROVADA"].includes(selected.status) && ["admin", "supervisor"].includes(user.role) &&
          h("button", { className: "ent-text-action danger", onClick: cancel }, "Cancelar transferência")
      )
    )
  );
}

function ExceptionsPage({ workspace, user, run }) {
  const open = workspace.exceptions.filter((item) => item.status === "ABERTA");
  const [filter, setFilter] = React.useState("ABERTA");
  const rows = filter === "TODAS" ? workspace.exceptions : workspace.exceptions.filter((item) => item.status === filter);
  async function resolve(item, resolution) {
    const reason = window.prompt("Justificativa obrigatória para a decisão:");
    if (!reason) return;
    await run(() => mutate(`/api/v2/exceptions/${item.id}/resolve`, { resolution, reason }, "exception-resolve"),
      "Divergência resolvida e auditada.");
  }
  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" }, h("div", null,
      h("h2", null, "Divergências"), h("p", null, `${open.length} ocorrência(s) aguardando decisão do supervisor.`)),
      h("select", { value: filter, onChange: (e) => setFilter(e.target.value) },
        h("option", { value: "ABERTA" }, "Abertas"), h("option", { value: "RESOLVIDA" }, "Resolvidas"), h("option", { value: "TODAS" }, "Todas"))),
    h(Panel, { title: "Fila de aprovação", subtitle: "Nenhuma decisão apaga o registro original" },
      rows.length ? h("div", { className: "ent-exception-list" }, rows.map((item) =>
        h("article", { key: item.id },
          h("div", { className: "ent-exception-icon" }, "!"),
          h("div", { className: "ent-exception-copy" },
            h("div", null, h("strong", null, humanStatus(item.tipo)), h(Status, { value: item.status })),
            h("p", null, item.descricao),
            h("small", null, `${item.operacao_tipo} · ${item.filial} · ${date(item.criado_em)} · ${number(item.quantidade)} un.`),
            item.justificativa && h("blockquote", null, item.justificativa)),
          item.status === "ABERTA" && ["admin", "supervisor"].includes(user.role) && h("div", { className: "ent-exception-actions" },
            ["EXCESSO", "AVARIA"].includes(item.tipo) && h("button", { className: "primary-action compact", onClick: () => resolve(item, "LIBERAR_ESTOQUE") }, "Liberar no estoque"),
            ["EXCESSO", "AVARIA"].includes(item.tipo) && h("button", { className: "secondary-action compact", onClick: () => resolve(item, "DEVOLVER") }, "Devolver"),
            item.tipo === "FALTA" && h("button", { className: "primary-action compact", onClick: () => resolve(item, "ACEITAR_FALTA") }, "Aceitar falta"),
            h("button", { className: "secondary-action compact", onClick: () => resolve(item, "CORRIGIDO") }, "Marcar corrigido")))
      )) : h(Empty, null, "Nenhuma divergência neste filtro.")
    )
  );
}

function AuditPage({ workspace, branchId, run }) {
  const [search, setSearch] = React.useState("");
  const [reconcile, setReconcile] = React.useState(null);
  const rows = workspace.movements.filter((item) =>
    `${item.sku} ${item.codigo_interno} ${item.descricao} ${item.tipo} ${item.origem_id}`.toLowerCase().includes(search.toLowerCase()));
  async function check() {
    const result = await run(async () => {
      const response = await enterpriseJson(`/api/v2/inventory/reconcile?branchId=${encodeURIComponent(branchId)}`);
      setReconcile(response);
      return response;
    }, "Reconciliação concluída.");
    if (result) setReconcile(result);
  }
  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" },
      h("div", null, h("h2", null, "Auditoria de estoque"), h("p", null, "Cada saldo pode ser explicado desde sua abertura.")),
      h("button", { className: "primary-action compact", onClick: check }, "Reconciliar saldos")),
    reconcile && h("div", { className: `ent-alert ${reconcile.ok ? "success" : "danger"}` },
      h("strong", null, reconcile.ok ? "Livro e saldos conciliados" : "Diferenças encontradas"),
      h("span", null, reconcile.ok ? "Nenhuma inconsistência foi identificada." : `${reconcile.differences.length} saldo(s) precisam de análise.`)),
    h(Panel, { title: "Livro de movimentos", subtitle: `${rows.length} lançamento(s) recentes`,
      action: h("input", { className: "ent-search", value: search, onChange: (e) => setSearch(e.target.value), placeholder: "Buscar produto, tipo ou origem" }) },
      h(DataTable, { rows, columns: [
        { key: "ocorrido_em", label: "Data", render: (row) => date(row.ocorrido_em) },
        { key: "tipo", label: "Movimento", render: (row) => h(Status, { value: row.tipo }) },
        { key: "codigo_interno", label: "Código" }, { key: "descricao", label: "Produto" },
        { key: "delta_fisico", label: "Δ físico", render: (row) => signed(row.delta_fisico) },
        { key: "delta_disponivel", label: "Δ disponível", render: (row) => signed(row.delta_disponivel) },
        { key: "delta_reservado", label: "Δ reservado", render: (row) => signed(row.delta_reservado) },
        { key: "criado_por", label: "Responsável" }
      ] })
    ),
    h(Panel, { title: "Trilha de auditoria", subtitle: `${(workspace.audits || []).length} evento(s) recentes` },
      h(DataTable, { rows: workspace.audits || [], columns: [
        { key: "criado_em", label: "Data", render: (row) => date(row.criado_em) },
        { key: "acao", label: "Ação", render: (row) => h(Status, { value: row.acao }) },
        { key: "entidade_tipo", label: "Entidade" },
        { key: "entidade_id", label: "Referência" },
        { key: "usuario_id", label: "Responsável" },
        { key: "filial", label: "Filial" }
      ] })
    )
  );
}

function CatalogPage({ workspace, branchId, user, run, legacyUsers }) {
  const [tab, setTab] = React.useState("products");
  const [product, setProduct] = React.useState({ sku: "", internalCode: "", description: "", eans: "" });
  const [branch, setBranch] = React.useState({ code: "", name: "", taxId: "" });
  const [assignment, setAssignment] = React.useState({ userId: "", branchId: "" });
  const [printer, setPrinter] = React.useState({ name: "", manufacturer: "ZEBRA", widthMm: 60, heightMm: 40, dpi: 203, branchId: "" });
  const [parameter, setParameter] = React.useState({ key: "operacao.fila_offline_limite", value: "500", branchId: "" });
  async function saveProduct(event) {
    event.preventDefault();
    const result = await run(() => mutate("/api/v2/products", product, "save-product"), "Produto salvo.");
    if (result) setProduct({ sku: "", internalCode: "", description: "", eans: "" });
  }
  async function saveBranch(event) {
    event.preventDefault();
    const result = await run(() => mutate("/api/v2/branches", branch, "create-branch"), "Filial cadastrada.");
    if (result) setBranch({ code: "", name: "", taxId: "" });
  }
  async function assignUser(event) {
    event.preventDefault();
    await run(() => mutate("/api/v2/profiles/branch", assignment, "assign-branch", false, "PATCH"),
      "Usuário vinculado à filial.");
  }
  async function savePrinter(event) {
    event.preventDefault();
    const targetBranch = printer.branchId || (branchId === "all" ? workspace.branches[0]?.id : branchId);
    const result = await run(() => mutate("/api/v2/printers", { ...printer, branchId: targetBranch },
      "create-printer"), "Impressora cadastrada.");
    if (result) setPrinter({ name: "", manufacturer: "ZEBRA", widthMm: 60, heightMm: 40, dpi: 203, branchId: "" });
  }
  async function saveParameter(event) {
    event.preventDefault();
    const targetBranch = parameter.branchId || (branchId === "all" ? workspace.branches[0]?.id : branchId);
    let value;
    try { value = JSON.parse(parameter.value); } catch (_) { value = parameter.value; }
    await run(() => mutate(`/api/v2/parameters/${encodeURIComponent(parameter.key.trim())}`,
      { branchId: targetBranch, value }, "save-parameter", false, "PATCH"), "Parâmetro atualizado.");
  }
  return h("div", { className: "ent-page" },
    h("div", { className: "ent-page-actions" }, h("div", null,
      h("h2", null, "Cadastros"), h("p", null, "Produtos, EANs e estrutura multi-filial."))),
    h("div", { className: "ent-tabs" },
      h("button", { className: tab === "products" ? "active" : "", onClick: () => setTab("products") }, "Produtos"),
      h("button", { className: tab === "branches" ? "active" : "", onClick: () => setTab("branches") }, "Filiais"),
      h("button", { className: tab === "printers" ? "active" : "", onClick: () => setTab("printers") }, "Impressoras"),
      h("button", { className: tab === "parameters" ? "active" : "", onClick: () => setTab("parameters") }, "Parâmetros")),
    tab === "products" && h("div", { className: "ent-two-columns catalog" },
      h(Panel, { title: "Novo produto", subtitle: "O código interno e cada EAN devem ser únicos" },
        h("form", { className: "ent-stack-form", onSubmit: saveProduct },
          h(Field, { label: "SKU do ERP" }, h("input", { required: true, value: product.sku, onChange: (e) => setProduct({ ...product, sku: e.target.value }) })),
          h(Field, { label: "Código interno" }, h("input", { required: true, value: product.internalCode, onChange: (e) => setProduct({ ...product, internalCode: e.target.value }) })),
          h(Field, { label: "Descrição" }, h("input", { required: true, value: product.description, onChange: (e) => setProduct({ ...product, description: e.target.value }) })),
          h(Field, { label: "EANs", hint: "Separe múltiplos códigos por vírgula" }, h("textarea", { rows: 3, value: product.eans, onChange: (e) => setProduct({ ...product, eans: e.target.value }) })),
          h("button", { className: "primary-action", type: "submit" }, "Salvar produto"))) ,
      h(Panel, { title: "Catálogo", subtitle: `${workspace.products.length} produto(s)` },
        h(DataTable, { rows: workspace.products, columns: [
          { key: "codigo_interno", label: "Código" }, { key: "sku", label: "SKU" },
          { key: "descricao", label: "Descrição" }, { key: "eans", label: "EANs" }
        ] }))
    ),
    tab === "branches" && h("div", { className: "ent-two-columns catalog" },
      user.role === "admin" && h(Panel, { title: "Nova filial" },
        h("form", { className: "ent-stack-form", onSubmit: saveBranch },
          h(Field, { label: "Código" }, h("input", { required: true, value: branch.code, onChange: (e) => setBranch({ ...branch, code: e.target.value }) })),
          h(Field, { label: "Nome" }, h("input", { required: true, value: branch.name, onChange: (e) => setBranch({ ...branch, name: e.target.value }) })),
          h(Field, { label: "CNPJ" }, h("input", { value: branch.taxId, onChange: (e) => setBranch({ ...branch, taxId: e.target.value }) })),
          h("button", { className: "primary-action", type: "submit" }, "Cadastrar filial"))),
      h(Panel, { title: "Filiais", subtitle: "Estrutura ativa" },
        h(DataTable, { rows: workspace.branches, columns: [
          { key: "codigo", label: "Código" }, { key: "nome", label: "Nome" },
          { key: "cnpj", label: "CNPJ" }, { key: "ativa", label: "Situação", render: (row) => h(Status, { value: row.ativa ? "ATIVA" : "INATIVA" }) }
        ] }))
    ),
    tab === "branches" && user.role === "admin" && h(Panel, { title: "Vincular usuários", subtitle: "Operadores e supervisores enxergam somente a filial atribuída" },
      h("form", { className: "ent-inline-form", onSubmit: assignUser },
        h(Field, { label: "Usuário" }, h("select", { required: true, value: assignment.userId, onChange: (e) => setAssignment({ ...assignment, userId: e.target.value }) },
          h("option", { value: "" }, "Selecione"), legacyUsers.filter((item) => item.role !== "admin").map((item) => h("option", { key: item.id, value: item.id }, `${item.name} · ${item.label}`)))),
        h(Field, { label: "Filial" }, h("select", { required: true, value: assignment.branchId, onChange: (e) => setAssignment({ ...assignment, branchId: e.target.value }) },
          h("option", { value: "" }, "Selecione"), workspace.branches.map((item) => h("option", { key: item.id, value: item.id }, item.nome)))),
        h("button", { className: "primary-action", type: "submit" }, "Vincular filial"))
    ),
    tab === "printers" && h("div", { className: "ent-two-columns catalog" },
      h(Panel, { title: "Nova impressora", subtitle: "Zebra, Argox ou impressão pelo navegador" },
        h("form", { className: "ent-stack-form", onSubmit: savePrinter },
          user.role === "admin" && h(Field, { label: "Filial" }, h("select", { required: true,
            value: printer.branchId || (branchId === "all" ? workspace.branches[0]?.id || "" : branchId),
            onChange: (e) => setPrinter({ ...printer, branchId: e.target.value }) },
            workspace.branches.map((item) => h("option", { key: item.id, value: item.id }, item.nome)))),
          h(Field, { label: "Nome" }, h("input", { required: true, value: printer.name,
            onChange: (e) => setPrinter({ ...printer, name: e.target.value }), placeholder: "Ex.: Zebra Recebimento 01" })),
          h(Field, { label: "Fabricante" }, h("select", { value: printer.manufacturer,
            onChange: (e) => setPrinter({ ...printer, manufacturer: e.target.value }) },
            h("option", { value: "ZEBRA" }, "Zebra"), h("option", { value: "ARGOX" }, "Argox"),
            h("option", { value: "NAVEGADOR" }, "Navegador / PDF"))),
          h(Field, { label: "Largura (mm)" }, h("input", { type: "number", min: 20, value: printer.widthMm,
            onChange: (e) => setPrinter({ ...printer, widthMm: Number(e.target.value) }) })),
          h(Field, { label: "Altura (mm)" }, h("input", { type: "number", min: 15, value: printer.heightMm,
            onChange: (e) => setPrinter({ ...printer, heightMm: Number(e.target.value) }) })),
          h(Field, { label: "Densidade (DPI)" }, h("select", { value: printer.dpi,
            onChange: (e) => setPrinter({ ...printer, dpi: Number(e.target.value) }) },
            h("option", { value: 203 }, "203 DPI"), h("option", { value: 300 }, "300 DPI"))),
          h("button", { className: "primary-action", type: "submit" }, "Cadastrar impressora"))),
      h(Panel, { title: "Impressoras", subtitle: `${(workspace.printers || []).length} configuração(ões)` },
        h(DataTable, { rows: workspace.printers || [], columns: [
          { key: "nome", label: "Nome" }, { key: "filial_nome", label: "Filial" },
          { key: "fabricante", label: "Fabricante" },
          { key: "size", label: "Etiqueta", render: (row) => `${row.largura_mm}×${row.altura_mm} mm · ${row.dpi} DPI` },
          { key: "ativa", label: "Situação", render: (row) => h(Status, { value: row.ativa ? "ATIVA" : "INATIVA" }) }
        ] }))
    ),
    tab === "parameters" && h("div", { className: "ent-two-columns catalog" },
      h(Panel, { title: "Parâmetro operacional", subtitle: "Configuração versionada por filial" },
        h("form", { className: "ent-stack-form", onSubmit: saveParameter },
          user.role === "admin" && h(Field, { label: "Filial" }, h("select", { required: true,
            value: parameter.branchId || (branchId === "all" ? workspace.branches[0]?.id || "" : branchId),
            onChange: (e) => setParameter({ ...parameter, branchId: e.target.value }) },
            workspace.branches.map((item) => h("option", { key: item.id, value: item.id }, item.nome)))),
          h(Field, { label: "Chave", hint: "Use uma chave estável, como operacao.fila_offline_limite" },
            h("input", { required: true, pattern: "[a-z0-9][a-z0-9._-]{1,79}", value: parameter.key,
              onChange: (e) => setParameter({ ...parameter, key: e.target.value.toLowerCase() }) })),
          h(Field, { label: "Valor", hint: "Aceita texto, número, verdadeiro/falso ou JSON" },
            h("textarea", { required: true, rows: 4, value: parameter.value,
              onChange: (e) => setParameter({ ...parameter, value: e.target.value }) })),
          h("button", { className: "primary-action", type: "submit" }, "Salvar parâmetro"))),
      h(Panel, { title: "Parâmetros ativos", subtitle: `${(workspace.parameters || []).length} configuração(ões)` },
        h(DataTable, { rows: workspace.parameters || [], columns: [
          { key: "chave", label: "Chave" }, { key: "valor", label: "Valor" },
          { key: "atualizado_por", label: "Responsável" },
          { key: "atualizado_em", label: "Atualizado", render: (row) => date(row.atualizado_em) }
        ] }))
    )
  );
}

function fileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("Não foi possível ler o arquivo."));
    reader.readAsDataURL(file);
  });
}

function parseItemLines(value) {
  const items = String(value || "").split(/\r?\n/).filter((line) => line.trim()).map((line, index) => {
    const [code, rawQuantity] = line.split(/[,;\t]/).map((part) => part.trim());
    const quantity = Number(rawQuantity);
    if (!code || !Number.isInteger(quantity) || quantity <= 0) throw new Error(`Linha ${index + 1} inválida. Use CODIGO,QUANTIDADE.`);
    return { code, quantity };
  });
  if (!items.length) throw new Error("Informe ao menos um item.");
  return items;
}

function sum(items, field) {
  return (items || []).reduce((total, item) => total + Number(item[field] || 0), 0);
}

function signed(value) {
  const amount = Number(value || 0);
  return h("span", { className: amount > 0 ? "ent-positive" : amount < 0 ? "ent-negative" : "" }, `${amount > 0 ? "+" : ""}${number(amount)}`);
}

function deviceId() {
  let value = localStorage.getItem("mnCheckDeviceId");
  if (!value) {
    value = crypto.randomUUID?.() || `device-${Date.now()}`;
    localStorage.setItem("mnCheckDeviceId", value);
  }
  return value;
}

function renderLabel(job) {
  const popup = window.open("", "_blank", "width=700,height=500");
  if (!popup) return;
  const labels = Array.from({ length: Number(job.labels || 1) }, () => `
    <article class="label">
      <strong>${escapeHtml(job.codigo_interno)}</strong>
      <div class="barcode">*${escapeHtml(job.codigo_interno)}*</div>
      <b>${escapeHtml(job.sku)}</b>
      <span>${escapeHtml(job.descricao)}</span>
      ${job.ean ? `<small>EAN ${escapeHtml(job.ean)}</small>` : ""}
    </article>`).join("");
  popup.document.write(`<!doctype html><html><head><title>Etiquetas MN Check</title><style>
    @page{size:${Number(job.widthMm || 60)}mm ${Number(job.heightMm || 40)}mm;margin:0}
    *{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif}.label{width:${Number(job.widthMm || 60)}mm;height:${Number(job.heightMm || 40)}mm;padding:3mm;display:flex;flex-direction:column;justify-content:center;text-align:center;page-break-after:always;overflow:hidden}.label>strong{font-size:22pt}.barcode{font-family:monospace;font-size:16pt;letter-spacing:2px}.label>b{font-size:10pt}.label>span,.label>small{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:8pt}</style></head><body>${labels}<script>window.onload=()=>window.print()<\/script></body></html>`);
  popup.document.close();
}

function escapeHtml(value) {
  return String(value || "").replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[character]));
}
