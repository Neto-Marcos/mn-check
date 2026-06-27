const CACHE_NAME = "mn-check-1.9.7";
const APP_ASSETS = [
  "/",
  "/index.html",
  "/styles.css?v=197",
  "/app.js?v=197",
  "/api.js",
  "/auth.js",
  "/conferencia.js",
  "/contagem.js",
  "/mapas.js",
  "/scanner.js",
  "/state.js",
  "/ui.js",
  "/logo.png?v=197",
  "/icon-192.png?v=197",
  "/apple-touch-icon.png?v=197",
  "/manifest.webmanifest?v=197",
  "/vendor/react.production.min.js",
  "/vendor/react-dom.production.min.js"
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_ASSETS)));
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  if (url.pathname.startsWith("/api/")) {
    event.respondWith(fetch(event.request));
    return;
  }

  event.respondWith(
    caches.match(event.request).then((cached) => {
      const network = fetch(event.request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
          }
          return response;
        })
        .catch(() => cached || caches.match("/index.html"));
      return cached || network;
    })
  );
});
