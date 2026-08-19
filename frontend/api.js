import { readStoredToken } from "./auth.js";

export async function authorizedJson(path, options = {}) {
  const token = readStoredToken();
  const response = await fetch(path, {
    method: options.method || "GET",
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.body !== undefined ? { "Content-Type": "application/json" } : {})
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Não foi possível carregar os dados.");
  return body;
}

export async function enterpriseJson(path, options = {}) {
  const token = readStoredToken();
  const headers = {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
    ...(options.idempotencyKey ? { "Idempotency-Key": options.idempotencyKey } : {})
  };
  const response = await fetch(path, {
    method: options.method || "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || "Não foi possível concluir a operação.");
  return body;
}

export function isNetworkFailure(error) {
  return !navigator.onLine
    || error instanceof TypeError
    || /failed to fetch|networkerror|network request failed/i.test(String(error?.message || ""));
}
