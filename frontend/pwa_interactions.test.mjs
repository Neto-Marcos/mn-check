import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  BOTTOM_NAV_PRIORITY,
  isLegacyRoutesLocation,
  isSabiumBlockedView,
  resolveFrontendView,
  supportedAllowedViews
} from "./state.js";
import {
  canStartPullToRefresh,
  isAtTop,
  PULL_TO_REFRESH_THRESHOLD,
  PullToRefreshGesture
} from "./pwa_interactions.js";

test("Rotas não é uma área navegável e links antigos caem na área válida", () => {
  const allowed = ["conference", "routes", "counting"];
  assert.equal(BOTTOM_NAV_PRIORITY.includes("routes"), false);
  assert.deepEqual(supportedAllowedViews(allowed), ["conference", "counting"]);
  assert.equal(resolveFrontendView("routes", allowed), "conference");
  assert.equal(isLegacyRoutesLocation("/routes"), true);
  assert.equal(isLegacyRoutesLocation("/routes/active", "?from=shortcut"), true);
  assert.equal(isLegacyRoutesLocation("/", "?view=routes"), true);
  assert.equal(isLegacyRoutesLocation("/counting"), false);
});

test("navegação desktop/mobile filtra rotas legadas e o módulo não é renderizado", async () => {
  const app = await readFile(new URL("./app.js", import.meta.url), "utf8");
  assert.match(app, /const navigationViews = \[\.\.\.supportedAllowedViews\(allowedViews\), "settings"\]/);
  assert.doesNotMatch(app, /view === "routes"/);
  assert.doesNotMatch(app, /action\("Rotas"/);
  assert.doesNotMatch(app, /function Routes\(/);
});

test("Separação e Conferência ficam sinalizadas como bloqueadas e caem no estado único", async () => {
  const [app, styles] = await Promise.all([
    readFile(new URL("./app.js", import.meta.url), "utf8"),
    readFile(new URL("./styles.css", import.meta.url), "utf8")
  ]);
  assert.equal(isSabiumBlockedView("separation"), true);
  assert.equal(isSabiumBlockedView("conference"), true);
  assert.equal(isSabiumBlockedView("counting"), false);
  assert.equal(isSabiumBlockedView("history"), false);
  assert.equal(resolveFrontendView("separation", ["separation"]), "separation");
  assert.equal(resolveFrontendView("conference", ["conference"]), "conference");
  assert.match(app, /isSabiumBlockedView\(view\) && h\(SabiumIntegrationBlocked/);
  assert.match(app, /Disponível após integração/);
  assert.match(app, /Esta área depende da integração com o Sabium para ser liberada\./);
  assert.match(app, /Aguardando integração/);
  assert.doesNotMatch(app, /view === "separation" && h\(Separation/);
  assert.doesNotMatch(app, /view === "conference" && h\(Conference/);
  assert.match(app, /integration-blocked-nav/);
  assert.match(styles, /\.nav-item\.integration-blocked-nav/);
  assert.match(styles, /\.bottom-nav-item\.integration-blocked-nav/);
  assert.match(styles, /\.sabium-integration-blocked/);
});

test("pull-to-refresh só pode começar em dashboard/histórico e fora de controles", () => {
  assert.equal(canStartPullToRefresh({ view: "overview" }), true);
  assert.equal(canStartPullToRefresh({ view: "history" }), true);
  for (const view of ["counting", "investigation", "separation", "conference", "settings", "users"]) {
    assert.equal(canStartPullToRefresh({ view }), false, `${view} não deve aceitar pull-to-refresh`);
  }
  assert.equal(canStartPullToRefresh({ view: "overview", hasOverlay: true }), false);
  assert.equal(canStartPullToRefresh({ view: "history", target: { closest: () => ({}) } }), false);
  assert.equal(canStartPullToRefresh({ view: "overview", activeElement: { matches: () => true } }), false);
});

test("scroll da página ou de um painel impede armar pull-to-refresh", () => {
  assert.equal(isAtTop({}), true);
  assert.equal(isAtTop({ windowScrollY: 1 }), false);
  assert.equal(isAtTop({ documentScrollTop: 1 }), false);
  assert.equal(isAtTop({ scrollingElementScrollTop: 1 }), false);
  assert.equal(isAtTop({ ancestorScrollTop: 1 }), false);
});

test("pull curto, scroll para cima e movimento horizontal nunca atualizam", () => {
  const gesture = new PullToRefreshGesture();
  assert.equal(gesture.start({ x: 80, y: 20, atTop: true, enabled: true }), true);
  assert.equal(gesture.move({ x: 80, y: 90 }).armed, false);
  assert.equal(gesture.end({ x: 80, y: 90 }), false);

  gesture.start({ x: 80, y: 100, atTop: true, enabled: true });
  assert.equal(gesture.move({ x: 80, y: 40 }).active, false);
  assert.equal(gesture.end({ x: 80, y: 40 }), false);

  gesture.start({ x: 80, y: 20, atTop: true, enabled: true });
  assert.equal(gesture.move({ x: 120, y: 30 }).active, false);
  assert.equal(gesture.end({ x: 120, y: 30 }), false);
});

test("limiar de 132px arma com resistência e só dispara uma vez no touchend", () => {
  const gesture = new PullToRefreshGesture();
  const threshold = PULL_TO_REFRESH_THRESHOLD;
  assert.equal(gesture.start({ x: 20, y: 10, atTop: true, enabled: true }), true);
  const progress = gesture.move({ x: 20, y: 10 + threshold });
  assert.equal(progress.armed, true);
  assert.equal(progress.justArmed, true);
  assert.equal(progress.progress, 1);
  assert.ok(progress.displacement < threshold, "o indicador deve resistir ao deslocamento do dedo");
  assert.equal(gesture.end({ x: 20, y: 10 + threshold }), true);
  assert.equal(gesture.end({ x: 20, y: 10 + threshold }), false);
});

test("gesto cancelado, fora do topo ou multi-touch nunca atualiza", () => {
  const gesture = new PullToRefreshGesture();
  assert.equal(gesture.start({ x: 0, y: 0, atTop: false, enabled: true }), false);
  assert.equal(gesture.start({ x: 0, y: 0, atTop: true, enabled: false }), false);
  assert.equal(gesture.start({ x: 0, y: 0, atTop: true, enabled: true, touchCount: 2 }), false);
  gesture.start({ x: 0, y: 0, atTop: true, enabled: true });
  gesture.move({ x: 0, y: PULL_TO_REFRESH_THRESHOLD });
  gesture.cancel();
  assert.equal(gesture.end({ x: 0, y: PULL_TO_REFRESH_THRESHOLD }), false);
});

test("PWA mantém cache, mas não força skipWaiting, reload ao trocar controller ou faixa de update", async () => {
  const [sw, app] = await Promise.all([
    readFile(new URL("./sw.js", import.meta.url), "utf8"),
    readFile(new URL("./app.js", import.meta.url), "utf8")
  ]);
  assert.match(sw, /addEventListener\("install"/);
  assert.match(sw, /addEventListener\("activate"/);
  assert.match(app, /navigator\.serviceWorker\.register/);
  assert.match(app, /swRegistrationRef\.current\?\.update\(\)/);
  assert.doesNotMatch(sw, /skipWaiting\s*\(/);
  assert.doesNotMatch(app, /controllerchange/);
  assert.doesNotMatch(app, /SKIP_WAITING/);
  assert.doesNotMatch(app, /Nova atualização disponível|Atualizar agora/);
});

test("safe-area cobre iOS Home Indicator sem margem fixa específica de aparelho", async () => {
  const [html, css] = await Promise.all([
    readFile(new URL("./index.html", import.meta.url), "utf8"),
    readFile(new URL("./styles.css", import.meta.url), "utf8")
  ]);
  assert.match(html, /viewport-fit=cover/);
  assert.match(html, /href="\/styles\.css/);
  assert.match(html, /src="\/app\.js/);
  assert.match(css, /bottom:\s*calc\(8px\s*\+\s*env\(safe-area-inset-bottom,\s*0px\)\)/);
  assert.match(css, /padding-bottom:\s*calc\(98px\s*\+\s*env\(safe-area-inset-bottom,\s*0px\)\)/);
  assert.match(css, /overscroll-behavior-y:\s*contain/);
});
