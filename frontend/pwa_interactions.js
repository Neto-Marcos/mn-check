export const PULL_TO_REFRESH_THRESHOLD = 132;

const PULL_TO_REFRESH_VIEWS = new Set(["overview", "history"]);
const INTERACTIVE_TARGET_SELECTOR = "input, textarea, select, button, a, form, [contenteditable='true'], [role='button'], [role='dialog'], .modal-backdrop";

export function canStartPullToRefresh({ view, target, activeElement, hasOverlay = false }) {
  if (!PULL_TO_REFRESH_VIEWS.has(view) || hasOverlay) return false;
  if (target?.closest?.(INTERACTIVE_TARGET_SELECTOR)) return false;
  if (activeElement?.matches?.("input, textarea, select, [contenteditable='true']")) return false;
  return true;
}

export function isAtTop({ windowScrollY = 0, documentScrollTop = 0, scrollingElementScrollTop = 0, ancestorScrollTop = 0 }) {
  return windowScrollY <= 0 && documentScrollTop <= 0 && scrollingElementScrollTop <= 0 && ancestorScrollTop <= 0;
}

export class PullToRefreshGesture {
  constructor(threshold = PULL_TO_REFRESH_THRESHOLD) {
    this.threshold = threshold;
    this.startPoint = null;
    this.armed = false;
  }

  start({ x, y, touchCount = 1, atTop, enabled }) {
    this.cancel();
    if (!enabled || !atTop || touchCount !== 1) return false;
    this.startPoint = { x, y };
    return true;
  }

  move({ x, y, touchCount = 1 }) {
    if (!this.startPoint || touchCount !== 1) return this.cancelResult();
    const deltaX = x - this.startPoint.x;
    const deltaY = y - this.startPoint.y;
    if (Math.abs(deltaX) > 8 && Math.abs(deltaX) >= Math.abs(deltaY)) return this.cancelResult();
    if (deltaY <= 0) return this.cancelResult();

    const wasArmed = this.armed;
    this.armed = deltaY >= this.threshold;
    return {
      active: true,
      armed: this.armed,
      justArmed: this.armed && !wasArmed,
      progress: Math.min(1, deltaY / this.threshold),
      displacement: Math.min(32, deltaY * 0.24)
    };
  }

  end({ x, y, touchCount = 1 } = {}) {
    const start = this.startPoint;
    const deltaX = start && Number.isFinite(x) ? x - start.x : 0;
    const deltaY = start && Number.isFinite(y) ? y - start.y : 0;
    const shouldRefresh = Boolean(start && touchCount === 1 && this.armed &&
      Math.abs(deltaX) < Math.max(8, Math.abs(deltaY)) && deltaY >= this.threshold);
    this.cancel();
    return shouldRefresh;
  }

  cancel() {
    this.startPoint = null;
    this.armed = false;
  }

  cancelResult() {
    this.cancel();
    return { active: false, armed: false, progress: 0, displacement: 0 };
  }
}
