const CACHE_NAME = "mn-check-1.8.9";
const APP_ASSETS = [
  "/",
  "/index.html",
  "/styles.css?v=189",
  "/app.js?v=189",
  "/logo.png?v=189",
  "/icon-192.png?v=189",
  "/apple-touch-icon.png?v=189",
  "/manifest.webmanifest?v=189",
  "/vendor/react.production.min.js",
  "/vendor/react-dom.production.min.js"
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_ASSETS)));
  self.skipWaiting();
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
