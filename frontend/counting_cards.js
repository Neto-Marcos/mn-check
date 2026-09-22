import { countAccounted, countDifference, hasCountMovement } from "./contagem.js";
import { voltageFromSku } from "./scanner.js";

const React = window.React;
const h = React.createElement;

export const CATEGORY_DEFINITIONS = [
  { id: "tv", label: "TVs", regex: /^(TV|SMART|TELEVISOR)\b|\bTV\b/i },
  { id: "refri", label: "Refrigeradores", regex: /^(REFRIGERADOR|GELADEIRA)\b/i },
  { id: "lav", label: "Lavadoras", regex: /^(LAVADORA|LAVA\s*E\s*SECA|TANQUINHO|CENTRIFUGA)\b/i },
  { id: "fogao", label: "Fogões", regex: /^(FOGAO|FOGÃO|COOKTOP)\b/i },
  { id: "vent", label: "Ventilação", regex: /^(VENTILADOR|CIRCULADOR|CLIMATIZADOR|AR\s*CONDICIONADO|SPLIT)\b/i },
  { id: "freezer", label: "Freezers", regex: /^(FREEZER|CONSERVADOR|FRIGOBAR)\b/i },
  { id: "micro", label: "Micro-ondas", regex: /^(MICRO-ONDAS|MICROONDAS|FORNO)\b/i },
  { id: "portateis", label: "Portáteis", regex: /^(FRITADEIRA|AIR\s*FRYER|LIQUIDIFICADOR|SANDUICHEIRA|PANELA|BATEDEIRA|CAFETEIRA|FERRO|GRILL|BEBEDOURO)\b/i }
];

export function detectProductCategory(description) {
  const desc = String(description || "").trim();
  for (let i = 0; i < CATEGORY_DEFINITIONS.length; i++) {
    if (CATEGORY_DEFINITIONS[i].regex.test(desc)) return CATEGORY_DEFINITIONS[i].id;
  }
  return "outros";
}

export function CountCard({
  item,
  searchDigits,
  countInputRefs,
  countExpressionValue,
  editCountExpression,
  commitCountExpression,
  adjustCountField,
  changeCountField
}) {
  const skuParts = String(item.sku).split(".");
  const color = skuParts[1] || "";
  const voltCode = skuParts[2] || "";
  const voltage = voltageFromSku(item.sku);
  const diff = countDifference(item);
  const moved = hasCountMovement(item);
  const isFound = searchDigits && (
    String(item.sku).replace(/\D/g, "") === searchDigits ||
    String(item.sku).replace(/\D/g, "").startsWith(searchDigits)
  );

  let statusClass = "pending";
  let statusText = "Não contado";
  if (moved) {
    if (diff === 0) {
      statusClass = "ok";
      statusText = "✔ Conforme";
    } else if (diff < 0) {
      statusClass = "missing";
      statusText = `Falta -${Math.abs(diff)}`;
    } else {
      statusClass = "surplus";
      statusText = `Sobra +${diff}`;
    }
  }

  const [expandedAvaria, setExpandedAvaria] = React.useState(
    item.damaged > 0 || item.assistance > 0 || item.other !== 0
  );

  return h("article", {
    key: item.sku,
    className: `count-card ${moved && diff === 0 ? "card-ok" : ""} ${diff !== 0 ? "card-divergent" : ""} ${isFound ? "card-found" : ""}`,
    id: `card-${item.sku.replace(/\./g, "-")}`
  },
    h("div", { className: "count-card-header" },
      h("div", { className: "count-card-id" },
        h("strong", { className: "count-sku-code" }, item.sku),
        voltage && voltage !== "Não informado" && h("span", { className: `count-tag-voltage volt-${voltCode}` }, voltage),
        color && color !== "—" && h("span", { className: "count-tag-color" }, `Cor ${color}`)
      ),
      h("span", { className: `count-status-badge badge-${statusClass}` }, statusText)
    ),

    h("h4", { className: "count-card-title" }, item.description || "Produto sem descrição"),

    h("div", { className: "count-card-balance-bar" },
      h("div", { className: "balance-pill system" },
        h("span", null, "Saldo ERP"),
        h("strong", null, item.system)
      ),
      h("div", { className: "balance-pill accounted" },
        h("span", null, "Apurado"),
        h("strong", null, countAccounted(item))
      ),
      h("div", { className: `balance-pill difference ${diff === 0 ? "diff-ok" : (diff < 0 ? "diff-neg" : "diff-pos")}` },
        h("span", null, "Diferença"),
        h("strong", null, diff === 0 ? "0" : (diff > 0 ? `+${diff}` : diff))
      )
    ),

    h("div", { className: "count-stepper-main" },
      h("div", { className: "count-stepper-label-row" },
        h("label", null, "Contagem física (boas)"),
        item.counted > 0 && h("button", {
          type: "button",
          className: "count-quick-zero",
          onClick: () => changeCountField(item.sku, "counted", 0),
          title: "Zerar física deste item"
        }, "Zerar")
      ),
      h("div", { className: "count-stepper-controls" },
        h("button", {
          type: "button",
          className: "stepper-btn step-minus",
          disabled: item.counted <= 0,
          onClick: () => adjustCountField(item.sku, "counted", -1),
          title: "Diminuir 1"
        }, "-1"),
        h("input", {
          className: "stepper-input",
          type: "text",
          inputMode: "numeric",
          value: countExpressionValue(item, "counted"),
          ref: (element) => {
            if (element) countInputRefs.current[item.sku] = element;
          },
          onChange: (event) => editCountExpression(item.sku, "counted", event.target.value),
          onBlur: () => commitCountExpression(item.sku, "counted", item.counted),
          onKeyDown: (event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              commitCountExpression(item.sku, "counted", item.counted);
            }
          }
        }),
        h("button", {
          type: "button",
          className: "stepper-btn step-plus",
          onClick: () => adjustCountField(item.sku, "counted", 1),
          title: "Adicionar 1"
        }, "+1"),
        h("button", {
          type: "button",
          className: "stepper-btn step-quick",
          onClick: () => adjustCountField(item.sku, "counted", 5),
          title: "Adicionar 5"
        }, "+5"),
        h("button", {
          type: "button",
          className: "stepper-btn step-quick",
          onClick: () => adjustCountField(item.sku, "counted", 10),
          title: "Adicionar 10"
        }, "+10")
      )
    ),

    h("div", { className: "count-card-secondary" },
      h("div", { className: "count-secondary-head" },
        h("button", {
          type: "button",
          className: "toggle-secondary-btn",
          onClick: () => setExpandedAvaria((curr) => !curr)
        },
          h("span", null, expandedAvaria ? "▾ Ocultar Avaria / Assistência" : "▸ Lançar Avaria / Assistência"),
          (item.damaged > 0 || item.assistance > 0 || item.other !== 0) && h("span", { className: "secondary-active-badge" },
            `${item.damaged > 0 ? `Avaria: ${item.damaged} ` : ""}${item.assistance > 0 ? `Assist: ${item.assistance}` : ""}${item.other !== 0 ? ` Outros: ${item.other}` : ""}`.trim()
          )
        )
      ),

      expandedAvaria && h("div", { className: "count-secondary-body" },
        h("div", { className: `secondary-stepper-block damaged ${item.damaged > 0 ? "has-value" : ""}` },
          h("div", { className: "secondary-label-row" },
            h("span", null, "Avaria"),
            h("strong", null, item.damaged)
          ),
          h("div", { className: "secondary-stepper-actions" },
            h("button", {
              type: "button",
              className: "sec-step-btn",
              disabled: item.damaged <= 0,
              onClick: () => adjustCountField(item.sku, "damaged", -1)
            }, "-"),
            h("input", {
              className: "sec-step-input",
              type: "text",
              inputMode: "numeric",
              value: countExpressionValue(item, "damaged"),
              onChange: (event) => editCountExpression(item.sku, "damaged", event.target.value),
              onBlur: () => commitCountExpression(item.sku, "damaged", item.damaged),
              onKeyDown: (event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  commitCountExpression(item.sku, "damaged", item.damaged);
                }
              }
            }),
            h("button", {
              type: "button",
              className: "sec-step-btn",
              onClick: () => adjustCountField(item.sku, "damaged", 1)
            }, "+")
          )
        ),

        h("div", { className: `secondary-stepper-block assistance ${item.assistance > 0 ? "has-value" : ""}` },
          h("div", { className: "secondary-label-row" },
            h("span", null, "Assistência"),
            h("strong", null, item.assistance)
          ),
          h("div", { className: "secondary-stepper-actions" },
            h("button", {
              type: "button",
              className: "sec-step-btn",
              disabled: item.assistance <= 0,
              onClick: () => adjustCountField(item.sku, "assistance", -1)
            }, "-"),
            h("input", {
              className: "sec-step-input",
              type: "text",
              inputMode: "numeric",
              value: countExpressionValue(item, "assistance"),
              onChange: (event) => editCountExpression(item.sku, "assistance", event.target.value),
              onBlur: () => commitCountExpression(item.sku, "assistance", item.assistance),
              onKeyDown: (event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  commitCountExpression(item.sku, "assistance", item.assistance);
                }
              }
            }),
            h("button", {
              type: "button",
              className: "sec-step-btn",
              onClick: () => adjustCountField(item.sku, "assistance", 1)
            }, "+")
          )
        ),

        h("div", { className: `secondary-stepper-block other ${item.other !== 0 ? "has-value" : ""}` },
          h("div", { className: "secondary-label-row" },
            h("span", null, "Outros"),
            h("strong", null, item.other > 0 ? `+${item.other}` : item.other)
          ),
          h("div", { className: "secondary-stepper-actions" },
            h("button", {
              type: "button",
              className: "sec-step-btn",
              onClick: () => adjustCountField(item.sku, "other", -1)
            }, "-"),
            h("input", {
              className: "sec-step-input",
              type: "text",
              inputMode: "numeric",
              value: countExpressionValue(item, "other"),
              onChange: (event) => editCountExpression(item.sku, "other", event.target.value),
              onBlur: () => commitCountExpression(item.sku, "other", item.other),
              onKeyDown: (event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  commitCountExpression(item.sku, "other", item.other);
                }
              }
            }),
            h("button", {
              type: "button",
              className: "sec-step-btn",
              onClick: () => adjustCountField(item.sku, "other", 1)
            }, "+")
          )
        )
      )
    )
  );
}

export function CountToolbar({
  viewMode,
  onChangeViewMode,
  countFilter,
  onSelectCountFilter,
  countFilterOptions,
  categoryFilter,
  onSelectCategoryFilter,
  activeCategories
}) {
  return h("div", { className: "count-toolbar" },
    h("div", { className: "count-view-toggle-bar" },
      h("div", { className: "count-view-toggle" },
        h("button", {
          type: "button",
          className: `count-toggle-btn ${viewMode === "cards" ? "active" : ""}`,
          onClick: () => onChangeViewMode("cards")
        }, "📱 Modo Cartões"),
        h("button", {
          type: "button",
          className: `count-toggle-btn ${viewMode === "table" ? "active" : ""}`,
          onClick: () => onChangeViewMode("table")
        }, "📊 Tabela")
      ),

      h("div", { className: "count-status-pills" },
        countFilterOptions.map((opt) => h("button", {
          key: opt.value,
          type: "button",
          className: `count-pill ${countFilter === opt.value ? "active" : ""} ${opt.value === "divergent" ? "pill-divergent" : ""} ${opt.value === "ok" ? "pill-ok" : ""}`,
          onClick: () => onSelectCountFilter(opt.value)
        },
          h("span", null, opt.label),
          h("span", { className: "count-pill-badge" }, opt.count)
        ))
      )
    ),

    activeCategories.length > 1 && h("div", { className: "category-pills-bar", "aria-label": "Filtrar por categoria" },
      activeCategories.map((cat) => h("button", {
        key: cat.id,
        type: "button",
        className: `category-pill ${categoryFilter === cat.id ? "active" : ""}`,
        onClick: () => onSelectCategoryFilter(cat.id)
      },
        h("span", null, cat.label),
        h("span", { className: "category-pill-count" }, `(${cat.count})`)
      ))
    )
  );
}

export function CountMobileHud({
  draft,
  countedItems,
  divergentRows,
  savingCount,
  onSubmitCount
}) {
  return h("div", { className: "count-mobile-hud" },
    h("div", { className: "hud-stats" },
      h("span", null, `${countedItems.length}/${draft.length} contados`),
      divergentRows.length > 0 && h("span", { className: "hud-divergent" }, `${divergentRows.length} div.`)
    ),
    h("button", {
      type: "button",
      className: "primary-action compact hud-save-btn",
      disabled: !draft.length || savingCount,
      onClick: onSubmitCount
    }, savingCount ? "Salvando..." : "Salvar")
  );
}
