import { readStoredToken } from "./auth.js";

export async function authorizedJson(path) {
  const token = readStoredToken();
  const response = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "Não foi possível carregar os dados.");
  return body;
}

export function isNetworkFailure(error) {
  return !navigator.onLine
    || error instanceof TypeError
    || /failed to fetch|networkerror|network request failed/i.test(String(error?.message || ""));
}
