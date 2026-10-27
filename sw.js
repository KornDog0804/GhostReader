// Version is bumped on every deploy; the page shows it at the bottom of the library.
const VERSION = "20261026-2248";
const CACHE = "ghostreader-" + VERSION;
const SHELL = ["./", "index.html", "style.css", "app.js", "page-engine.js", "zip.js", "epub.js", "db.js", "covers.js", "manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png"];
self.addEventListener("install", (e) => {
  // cache:"reload" skips Safari's own HTTP cache so the new files are really the new files.
  e.waitUntil(caches.open(CACHE).then((c) => Promise.all(SHELL.map((u) => fetch(new Request(u, { cache: "reload" })).then((r) => c.put(u, r))))).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => { e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("message", (e) => { if (e.data === "version") e.source.postMessage({ version: VERSION }); });
self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin) return;
  // Always ask the network (no browser cache) so a push shows up right away; the cache is only for offline.
  e.respondWith(fetch(new Request(req, { cache: "no-store" })).then((res) => { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); return res; }).catch(() => caches.match(req).then((r) => r || caches.match("index.html"))));
});
