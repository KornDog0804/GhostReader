// Sync books, covers and reading progress through the user's own Cloudflare Worker (R2 storage).
import * as DB from "./db.js";

const MAX_FILE = 95 * 1024 * 1024; // Workers accept request bodies up to 100 MB on the free plan.
const LS = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
export const getConfig = () => LS.get("gr:sync", null);
export const setConfig = (c) => (c ? LS.set("gr:sync", c) : localStorage.removeItem("gr:sync"));

const sha1 = async (s) => [...new Uint8Array(await crypto.subtle.digest("SHA-1", new TextEncoder().encode(s)))].map((b) => b.toString(16).padStart(2, "0")).join("");
const kindOf = (store, it) => (store === "audio" ? "a" : it.format === "cbz" || it.format === "pdf" ? "c" : "b");

class Remote {
  constructor(cfg) { this.base = cfg.url.replace(/\/+$/, ""); this.h = { Authorization: "Bearer " + cfg.key }; }
  async req(path, opts = {}) {
    const r = await fetch(this.base + path, { ...opts, headers: { ...this.h, ...(opts.headers || {}) } });
    if (r.status === 401) throw new Error("Wrong sync key");
    return r;
  }
  async ping() { const r = await this.req("/ping"); if (!r.ok) throw new Error("Sync server said " + r.status); }
  async list(prefix) { const r = await this.req("/list?prefix=" + encodeURIComponent(prefix)); if (!r.ok) throw new Error("List failed (" + r.status + ")"); return (await r.json()).keys; }
  async getJson(key) { const r = await this.req("/o/" + encodeURIComponent(key)); return r.ok ? r.json() : null; }
  async getText(key) { const r = await this.req("/o/" + encodeURIComponent(key)); return r.ok ? r.text() : null; }
  async getBlob(key) { const r = await this.req("/o/" + encodeURIComponent(key)); if (!r.ok) throw new Error("Download failed (" + r.status + ")"); return r.blob(); }
  async put(key, body, type) { const r = await this.req("/o/" + encodeURIComponent(key), { method: "PUT", body, headers: { "Content-Type": type } }); if (!r.ok) throw new Error("Upload failed (" + r.status + ")"); }
}

let running = false;
export const isSyncing = () => running;

/** One full two-way sync. Returns a summary string. */
export async function syncNow(onStatus = () => {}) {
  const cfg = getConfig();
  if (!cfg?.url || !cfg?.key) throw new Error("Sync isn't set up yet");
  if (running) return "Already syncing";
  running = true;
  const sum = { up: 0, down: 0, skipped: 0 };
  try {
    const R = new Remote(cfg);
    onStatus("Connecting…");
    await R.ping();
    const local = [];
    for (const store of ["books", "audio"]) for (const it of await DB.all(store)) local.push({ store, it, hash: await sha1(`${store}|${it.name}|${it.size}`) });
    const remoteMeta = new Set((await R.list("m/")).map((k) => k.slice(2, 42)));
    const have = new Set(local.map((x) => x.hash));

    // 1) upload what the server doesn't have yet (audiobooks stay on-device: too big for the free Worker upload limit)
    let n = 0;
    for (const x of local) {
      if (x.store === "audio" || remoteMeta.has(x.hash)) continue;
      n++;
      if (x.it.blob.size > MAX_FILE) { sum.skipped++; continue; }
      onStatus(`Uploading ${n}: ${x.it.title || x.it.name}…`);
      await R.put("f/" + x.hash, x.it.blob, "application/octet-stream");
      const cv = await DB.get("covers", `${x.store}:${x.it.id}`).catch(() => null);
      if (cv?.data) await R.put("c/" + x.hash, cv.data, "text/plain");
      await R.put(`m/${x.hash}.json`, JSON.stringify({ store: x.store, name: x.it.name, size: x.it.size, title: x.it.title, author: x.it.author, format: x.it.format || null, manga: !!x.it.manga, added: x.it.added || Date.now() }), "application/json");
      sum.up++;
    }

    // 2) download what this device doesn't have
    const toGet = [...remoteMeta].filter((h) => !have.has(h));
    let d = 0;
    for (const h of toGet) {
      d++;
      const m = await R.getJson(`m/${h}.json`);
      if (!m || m.store !== "books") continue;
      onStatus(`Downloading ${d} of ${toGet.length}: ${m.title || m.name}…`);
      const blob = await R.getBlob("f/" + h);
      const id = await DB.put("books", { name: m.name, size: m.size, title: m.title, author: m.author, format: m.format || undefined, manga: m.manga, cover: null, blob, added: m.added });
      const cv = await R.getText("c/" + h);
      if (cv) await DB.put("covers", { key: `books:${id}`, size: 0, data: cv, manual: true });
      local.push({ store: "books", it: { id, name: m.name, size: m.size, format: m.format }, hash: h });
      sum.down++;
    }

    // 3) covers: bring over artwork for local items that have none, and share covers picked by hand
    for (const x of local) {
      if (x.store !== "books") continue;
      const key = `books:${x.it.id}`;
      const cv = await DB.get("covers", key).catch(() => null);
      if (!cv && remoteMeta.has(x.hash)) { const t = await R.getText("c/" + x.hash); if (t) await DB.put("covers", { key, size: 0, data: t, manual: true }); }
      else if (cv?.manual && cv.data && remoteMeta.has(x.hash) && !cv.shared) { await R.put("c/" + x.hash, cv.data, "text/plain"); await DB.put("covers", { ...cv, shared: true }); }
    }

    // 4) reading progress: newest timestamp wins, in both directions
    onStatus("Syncing reading progress…");
    const state = (await R.getJson("state.json")) || {};
    let dirty = false;
    for (const x of local) {
      const kind = kindOf(x.store, x.it), lk = `gr:${kind}:${x.it.id}`, sk = `${kind}|${x.hash}`;
      const lp = LS.get(lk, null), rp = state[sk];
      if (lp?.ts && (!rp || lp.ts > rp.ts)) { state[sk] = lp; dirty = true; }
      else if (rp?.ts && (!lp || rp.ts > lp.ts)) LS.set(lk, rp);
    }
    if (dirty) await R.put("state.json", JSON.stringify(state), "application/json");

    LS.set("gr:synctime", Date.now());
    return `Synced. ${sum.up} sent, ${sum.down} received${sum.skipped ? `, ${sum.skipped} too big to sync` : ""}.`;
  } finally { running = false; }
}

/* ---------- audiobooks: opt-in, one at a time, in 50 MB pieces ---------- */
const PART = 50 * 1024 * 1024;
const audioMime = (n) => (/\.mp3$/i.test(n) ? "audio/mpeg" : "audio/mp4");
async function withRetry(fn, tries = 4) {
  let last;
  for (let i = 0; i < tries; i++) { try { return await fn(); } catch (e) { if (/Wrong sync key/.test(e.message)) throw e; last = e; await new Promise((r) => setTimeout(r, 1500 * (i + 1))); } }
  throw last;
}

/** Audiobooks that are in the cloud but not on this device. */
export async function listCloudAudio() {
  const cfg = getConfig();
  if (!cfg?.url || !cfg?.key) throw new Error("Sync isn't set up yet");
  const R = new Remote(cfg);
  await R.ping();
  const mine = new Set();
  for (const it of await DB.all("audio")) mine.add(await sha1(`audio|${it.name}|${it.size}`));
  const hashes = (await R.list("m/")).map((k) => k.slice(2, 42));
  const out = [];
  await Promise.all(hashes.map(async (h) => {
    const m = await R.getJson(`m/${h}.json`).catch(() => null);
    if (m && m.store === "audio" && m.parts) out.push({ hash: h, meta: m, here: mine.has(h) });
  }));
  return out.sort((a, b) => (a.meta.title || "").localeCompare(b.meta.title || ""));
}

/** Download one cloud audiobook onto this device. Returns the new local id. */
export async function downloadCloudAudio(hash, m, onStatus = () => {}) {
  const R = new Remote(getConfig());
  const parts = [];
  for (let i = 0; i < m.parts; i++) {
    onStatus(`Downloading part ${i + 1} of ${m.parts}…`);
    const key = "f/" + (await sha1(`${hash}|part|${i}`));
    parts.push(await withRetry(() => R.getBlob(key)));
  }
  const blob = new Blob(parts, { type: audioMime(m.name) });
  if (blob.size !== m.size) throw new Error("Download was incomplete, try again");
  const id = await DB.put("audio", { name: m.name, size: m.size, title: m.title, author: m.author || "", cover: null, blob, added: Date.now() });
  const cv = await R.getText("c/" + hash).catch(() => null);
  if (cv) await DB.put("covers", { key: `audio:${id}`, size: 0, data: cv, manual: true });
  return id;
}

/** Send one audiobook from this device to the cloud. */
export async function uploadCloudAudio(rec, onStatus = () => {}) {
  const R = new Remote(getConfig());
  const hash = await sha1(`audio|${rec.name}|${rec.size}`);
  const have = new Set(await R.list("f/"));
  const parts = Math.max(1, Math.ceil(rec.blob.size / PART));
  for (let i = 0; i < parts; i++) {
    const key = "f/" + (await sha1(`${hash}|part|${i}`));
    if (have.has(key)) continue;
    onStatus(`Uploading part ${i + 1} of ${parts}…`);
    await withRetry(() => R.put(key, rec.blob.slice(i * PART, (i + 1) * PART), "application/octet-stream"));
  }
  const cv = await DB.get("covers", `audio:${rec.id}`).catch(() => null);
  if (cv?.data) await R.put("c/" + hash, cv.data, "text/plain");
  await R.put(`m/${hash}.json`, JSON.stringify({ store: "audio", name: rec.name, size: rec.size, title: rec.title, author: rec.author || "", format: null, parts, partSize: PART, manga: false, added: Date.now() }), "application/json");
}
