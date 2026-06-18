export function readStoredToken() {
  return localStorage.getItem("mnCheckToken") || localStorage.getItem("mmJavaToken") || "";
}

export function storeToken(token) {
  localStorage.setItem("mnCheckToken", token);
  localStorage.removeItem("mmJavaToken");
}

export function clearStoredToken() {
  localStorage.removeItem("mnCheckToken");
  localStorage.removeItem("mmJavaToken");
}
