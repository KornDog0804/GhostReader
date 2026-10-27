import { Zip } from "./zip.js";
import { Epub } from "./epub.js";
import * as DB from "./db.js";
import { parseName, fetchCover } from "./covers.js";

const $ = (s) => document.querySelector(s);
const LS = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

let tab = "books";
let urls = [];
const setStatus = (t) => { const el = $("#status"); el.textContent = t; el.hidden = !t; };

/* ---------------- library ---------------- */
function progressOf(kind, id) { return LS.get(`gr:${kind}:${id}`, null); }

function posterHtml(item, kind, url) {
  const hue = [...(item.title || "x")].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7);
  const art = url ? `<img src="${url}" alt="">` : `<div class="gen" style="background:linear-gradient(160deg,hsl(${hue} 55% 38%),hsl(${(hue + 40) % 360} 70% 14%))">${esc(item.title)}<small>${esc(item.author)}</small></div>`;
  const p = progressOf(kind, item.id);
  const frac = kind === "b" ? (p?.o ?? 0) : kind === "c" ? (p?.o ?? 0) : p?.d ? Math.min(1, (p.t || 0) / p.d) : 0;
  const badge = kind === "a" ? '<span class="badge">🎧 Audio</span>' : "";
  return `<button class="card" data-kind="${kind}" data-id="${item.id}"><div class="poster">${art}${badge}${frac > 0.005 ? `<div class="bar-prog"><i style="width:${Math.round(frac * 100)}%"></i></div>` : ""}<span class="more" data-more="1">⋮</span></div><div class="ctitle">${esc(item.title)}</div><div class="cauthor">${esc(item.author)}</div></button>`;
}

async function renderLibrary() {
  urls.forEach((u) => URL.revokeObjectURL(u)); urls = [];
  const kind = tab === "books" ? "b" : tab === "audio" ? "a" : "c";
  const items = tab === "books" ? (await DB.all("books")).filter(x => !["cbz","pdf"].includes(x.format)) : tab === "audio" ? await DB.all("audio") : (await DB.all("books")).filter(x => x.format === "cbz" || x.format === "pdf").filter(x => tab === "manga" ? x.manga : !x.manga);
  const lastRead = (it) => progressOf(kind, it.id)?.ts || 0;
  items.sort((a, b) => lastRead(b) - lastRead(a) || (b.added || 0) - (a.added || 0));
  const url = (blob) => { if (!blob) return null; const u = URL.createObjectURL(blob); urls.push(u); return u; };
  $("#empty").hidden = items.length > 0;
  $("#grid").style.display = items.length ? "" : "none";
  const cont = items.filter((it) => { const p = progressOf(kind, it.id); const f = kind === "b" || kind === "c" ? p?.o : p?.d ? p.t / p.d : 0; return p && f > 0.005 && f < 0.97; }).slice(0, 8);
  $("#continue").innerHTML = cont.length ? `<h3 class="sec">Continue</h3><div class="row-scroll">${cont.map((it) => posterHtml(it, kind, url(it.cover))).join("")}</div><h3 class="sec">${tab === "books" ? "All books" : "All audiobooks"}</h3>` : "";
  $("#grid").innerHTML = items.map((it) => posterHtml(it, kind, url(it.cover))).join("");
}

document.addEventListener("click", (e) => {
  const card = e.target.closest(".card");
  if (card && $("#library").contains(card)) {
    const id = Number(card.dataset.id), kind = card.dataset.kind;
    if (e.target.closest("[data-more]")) return showMenu(e.target, kind, id);
    return kind === "b" ? openBook(id) : kind === "c" ? openComic(id) : openAudio(id);
  }
  if (!e.target.closest("#menu")) $("#menu").hidden = true;
});
document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => {
  tab = b.dataset.tab;
  document.querySelectorAll(".tabs button").forEach((x) => x.classList.toggle("on", x === b));
  renderLibrary();
}));

function showMenu(anchor, kind, id) {
  const m = $("#menu"), r = anchor.getBoundingClientRect();
  m.innerHTML = `<button data-act="cover">Find cover again</button><button data-act="del">Remove from this device</button>`;
  m.hidden = false;
  m.style.top = Math.min(r.bottom + 4, innerHeight - 110) + "px";
  m.style.left = Math.max(8, Math.min(r.right - 180, innerWidth - 190)) + "px";
  m.onclick = async (e) => {
    const act = e.target.dataset.act; if (!act) return;
    m.hidden = true;
    const store = kind === "a" ? "audio" : "books";
    if (act === "del") { if (confirm("Remove this from the tablet?")) { await DB.del(store, id); localStorage.removeItem(`gr:${kind}:${id}`); renderLibrary(); } }
    if (act === "cover") { const it = await DB.get(store, id); const c = await fetchCover(it.title, it.author); if (c) { it.cover = c; await DB.put(store, it); renderLibrary(); } else setStatus("No matching cover found."); setTimeout(() => setStatus(""), 3000); }
  };
}

/* ---------------- importing ---------------- */
const COMIC_EXT = /\.(cbz|pdf)$/i;
const AUDIO_EXT = /\.(m4b|m4a|mp3|ogg|opus|flac)$/i;

async function addEpub(blob, fileName) {
  const existing = await DB.all("books");
  if (existing.some((b) => b.name === fileName && b.size === blob.size)) return false;
  let title = "", author = "", cover = null;
  try {
    const ep = await Epub.open(blob);
    title = ep.title; author = ep.author; cover = await ep.cover(); ep.destroy();
  } catch { /* unreadable metadata: fall back to the file name */ }
  const parsed = parseName(fileName);
  if (!title) { title = parsed.title; author ||= parsed.author; }
  const id = await DB.put("books", { name: fileName, size: blob.size, title, author, cover, blob, added: Date.now() });
  if (!cover) fetchCover(title, author).then(async (c) => { if (c) { const it = await DB.get("books", id); it.cover = c; await DB.put("books", it); if (tab === "books") renderLibrary(); } });
  return true;
}
async function addAudio(blob, fileName) {
  const existing = await DB.all("audio");
  if (existing.some((a) => a.name === fileName && a.size === blob.size)) return false;
  const { title, author } = parseName(fileName);
  const id = await DB.put("audio", { name: fileName, size: blob.size, title, author, cover: null, blob, added: Date.now() });
  fetchCover(title, author).then(async (c) => { if (c) { const it = await DB.get("audio", id); it.cover = c; await DB.put("audio", it); if (tab === "audio") renderLibrary(); } });
  return true;
}

async function importZip(file) {
  const zip = await Zip.open(file);
  const names = zip.names();
  const nameFor = async (prefix, id, fallback) => zip.has(`${prefix}/${id}.txt`) ? (await zip.text(`${prefix}/${id}.txt`)).trim() : fallback;
  let added = 0, n = 0;
  const books = names.filter((x) => x.startsWith("books/") || /\.epub$/i.test(x));
  const audio = names.filter((x) => x.startsWith("audio/") || AUDIO_EXT.test(x));
  const total = books.length + audio.length;
  for (const p of books) {
    setStatus(`Adding book ${++n} of ${total}…`);
    const id = p.replace(/^books\//, "").replace(/\.epub$/i, "");
    const name = p.startsWith("books/") ? await nameFor("names", id, id + ".epub") : p.split("/").pop();
    if (await addEpub(await zip.blob_(p, "application/epub+zip"), name)) added++;
  }
  for (const p of audio) {
    const id = p.replace(/^audio\//, "").replace(/\.bin$/i, "");
    const name = p.startsWith("audio/") ? await nameFor("audionames", id, `audiobook ${id}.m4b`) : p.split("/").pop();
    setStatus(`Adding audiobook ${++n} of ${total} (this one is big, keep the app open)…`);
    if (await addAudio(await zip.blob_(p), name)) added++;
  }
  return added;
}

async function addComic(blob, fileName) {
 const existing = await DB.all("books");
 if (existing.some(x => x.name === fileName && x.size === blob.size)) return false;
 const format = fileName.toLowerCase().endsWith(".pdf") ? "pdf" : "cbz";
 let cover = null;
 if (format === "cbz") {
  const zip = await Zip.open(blob);
  const pages = zip.names().filter(x => /\.(jpe?g|png|webp|gif)$/i.test(x) && !x.startsWith("__MACOSX/")).sort((x,y) => x.localeCompare(y,undefined,{numeric:true,sensitivity:"base"}));
  if (!pages.length) throw new Error("CBZ has no image pages");
  cover = await zip.blob_(pages[0], "image/jpeg");
 }
 const parsed = parseName(fileName);
 await DB.put("books", { name:fileName,size:blob.size,title:parsed.title,author:parsed.author,cover,blob,format,manga:false,added:Date.now() });
 return true;
}
async function importFiles(files) {
  let added = 0;
  try {
    for (const f of files) {
      if (/\.epub$/i.test(f.name)) { setStatus("Adding " + f.name + "…"); if (await addEpub(f, f.name)) added++; }
      else if (COMIC_EXT.test(f.name)) { setStatus("Adding " + f.name + "…"); if (await addComic(f, f.name)) added++; }
      else if (/\.zip$/i.test(f.name)) { if (f.size === 0) { setStatus("That ZIP is empty (0 KB). Save the library again from the phone app."); return; } added += await importZip(f); }
      else if (AUDIO_EXT.test(f.name)) { setStatus("Adding " + f.name + "…"); if (await addAudio(f, f.name)) added++; }
    }
    setStatus(added ? `Added ${added} item${added === 1 ? "" : "s"}.` : "Nothing new to add.");
  } catch (err) {
    setStatus("Couldn't import: " + (err.message || err));
  }
  if (navigator.storage?.persist) navigator.storage.persist();
  await renderLibrary();
  setTimeout(() => setStatus(""), 6000);
}
for (const id of ["#add", "#add2"]) $(id).addEventListener("click", () => $("#file").click());
$("#file").addEventListener("change", (e) => { const fs = [...e.target.files]; e.target.value = ""; if (fs.length) importFiles(fs); });
// Opening a file with the installed app (Android file handler) and drag-drop on desktop.
addEventListener("dragover", (e) => e.preventDefault());
addEventListener("drop", (e) => { e.preventDefault(); if (e.dataTransfer?.files?.length) importFiles([...e.dataTransfer.files]); });
if ("launchQueue" in window) launchQueue.setConsumer(async (p) => { const fs = []; for (const h of p.files || []) fs.push(await h.getFile()); if (fs.length) importFiles(fs); });

/* ---------------- reader ---------------- */
const THEMES = {
  ghost: { bg: "#0a0614", ink: "#e9e3ff", bar: "#140b29", link: "#a78bfa" },
  night: { bg: "#000000", ink: "#b8b8b8", bar: "#0d0d0d", link: "#8ab4f8" },
  sepia: { bg: "#f4ecd8", ink: "#5b4636", bar: "#e8dcc0", link: "#8a4b14" },
  paper: { bg: "#ffffff", ink: "#111111", bar: "#eeeeee", link: "#1a56c4" },
};
const settings = Object.assign({ theme: "ghost", size: 20, font: "serif", spacing: 1.6, margin: 48 }, LS.get("gr:settings", {}));
const saveSettings = () => LS.set("gr:settings", settings);

let R = null; // current reader state: {epub,id,ci,page,pages,step}
const frame = $("#page");

function applyChrome() {
  const t = THEMES[settings.theme];
  const el = $("#reader");
  el.style.setProperty("--rbg", t.bg); el.style.setProperty("--rink", t.ink); el.style.setProperty("--rbar", t.bar);
  document.querySelector('meta[name="theme-color"]').content = t.bar;
  document.querySelectorAll("#themes button").forEach((b) => b.classList.toggle("on", b.dataset.v === settings.theme));
  document.querySelectorAll("#fonts button").forEach((b) => b.classList.toggle("on", b.dataset.v === settings.font));
  $("#spacing").value = settings.spacing; $("#margin").value = settings.margin;
}

async function openBook(id) {
  const rec = await DB.get("books", id);
  if (!rec) return;
  setStatus("Opening…");
  let epub;
  try { epub = await Epub.open(rec.blob); } catch (e) { setStatus("Couldn't open this book: " + e.message); return; }
  setStatus("");
  const saved = progressOf("b", id) || { c: 0, f: 0 };
  R = { epub, id, ci: Math.min(saved.c || 0, Math.max(0, epub.spine.length - 1)), page: 0, pages: 1, step: 1, title: rec.title };
  $("#library").hidden = true; $("#reader").hidden = false; $("#reader").classList.remove("chromeoff");
  $("#rt").textContent = rec.title;
  applyChrome();
  await showChapter(R.ci, { frac: saved.f || 0 });
}

function layoutCss() {
  const t = THEMES[settings.theme];
  const W = frame.clientWidth, H = frame.clientHeight;
  const m = settings.margin, vert = 64;
  const cols = W >= 1000 && W > H ? 2 : 1;
  const gap = cols === 2 ? Math.max(2 * m, 48) : 2 * m;
  const content = W - 2 * m;
  const colW = (content - (cols - 1) * gap) / cols;
  R.step = content + gap;
  const fam = settings.font === "serif" ? "Georgia,'Times New Roman',serif" : "system-ui,-apple-system,Segoe UI,Roboto,sans-serif";
  return `html{overflow:hidden;background:${t.bg}}
body{margin:0!important;padding:${vert}px ${m}px!important;height:${H - 2 * vert}px;box-sizing:content-box;column-width:${colW}px;column-gap:${gap}px;column-fill:auto;
  background:${t.bg}!important;color:${t.ink}!important;font-family:${fam}!important;font-size:${settings.size}px!important;line-height:${settings.spacing}!important;text-align:left;hyphens:auto;word-break:break-word}
body *{color:inherit!important;background:transparent!important;font-family:inherit!important;max-width:100%}
p,li,div,span,blockquote{font-size:inherit!important;line-height:inherit!important}
h1,h2,h3{line-height:1.25!important;break-after:avoid}h1{font-size:1.6em!important}h2{font-size:1.35em!important}h3{font-size:1.15em!important}
a{color:${t.link}!important;text-decoration:none}
img,svg{max-width:100%!important;max-height:${H - 2 * vert - 8}px!important;height:auto;object-fit:contain;break-inside:avoid;display:block;margin:.6em auto}
p{margin:0 0 .7em}`;
}

async function showChapter(ci, { frac = 0, end = false } = {}) {
  const { epub } = R;
  ci = Math.max(0, Math.min(epub.spine.length - 1, ci));
  R.ci = ci;
  const ch = await epub.chapterHtml(ci);
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>${ch.css.replace(/<\/style/gi, "")}</style><style>${layoutCss()}</style></head><body>${ch.body}</body></html>`;
  await new Promise((res) => { frame.onload = res; frame.srcdoc = html; });
  const doc = frame.contentDocument;
  await Promise.all([...doc.images].map((i) => (i.decode ? i.decode().catch(() => {}) : null)));
  doc.addEventListener("click", (e) => {
    const a = e.target.closest?.("a[data-epub]");
    if (a) { e.preventDefault(); const [p] = a.dataset.epub.split("#"); const i = epub.chapterIndexForPath(p); if (i >= 0) showChapter(i); return; }
    const x = e.clientX / frame.clientWidth;
    if (x < 0.28) turn(-1); else if (x > 0.72) turn(1); else toggleChrome();
  });
  let sx = 0, sy = 0;
  doc.addEventListener("touchstart", (e) => { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
  doc.addEventListener("touchend", (e) => { const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy; if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) turn(dx < 0 ? 1 : -1); }, { passive: true });
  R.pages = Math.max(1, Math.round(doc.documentElement.scrollWidth / R.step));
  R.page = end ? R.pages - 1 : Math.min(R.pages - 1, Math.floor(frac * R.pages));
  seek();
}
function seek() {
  frame.contentDocument.documentElement.scrollLeft = R.page * R.step;
  frame.contentWindow.scrollTo(R.page * R.step, 0);
  updateProgress();
}
function turn(d) {
  if (!R) return;
  const p = R.page + d;
  if (p < 0) { if (R.ci > 0) showChapter(R.ci - 1, { end: true }); return; }
  if (p >= R.pages) { if (R.ci < R.epub.spine.length - 1) showChapter(R.ci + 1); return; }
  R.page = p; seek();
}
function overall() { return Math.min(1, (R.ci + R.page / R.pages) / R.epub.spine.length) ; }
function updateProgress() {
  const o = R.ci === R.epub.spine.length - 1 && R.page >= R.pages - 1 ? 1 : overall();
  $("#rslide").value = Math.round(o * 1000);
  $("#rpct").textContent = Math.round(o * 100) + "%";
  $("#rc").textContent = R.epub.chapterTitle(R.ci);
  LS.set(`gr:b:${R.id}`, { c: R.ci, f: R.page / R.pages, o, ts: Date.now() });
}
const toggleChrome = () => $("#reader").classList.toggle("chromeoff");
// Transparent page-turn zones sit above the EPUB iframe on iPad Safari.
// Without handlers they swallow taps, leaving the book stuck on its first page.
$("#tapL").addEventListener("click", () => turn(-1));
$("#tapR").addEventListener("click", () => turn(1));
$("#tapM").addEventListener("click", toggleChrome);
$("#rslide").addEventListener("change", (e) => {
  const v = Number(e.target.value) / 1000 * R.epub.spine.length;
  showChapter(Math.floor(v), { frac: v - Math.floor(v) });
});
$("#rback").addEventListener("click", () => { R?.epub.destroy(); R = null; frame.srcdoc = ""; $("#reader").hidden = true; $("#library").hidden = false; document.querySelector('meta[name="theme-color"]').content = "#0a0614"; renderLibrary(); });
addEventListener("keydown", (e) => {
  if ($("#reader").hidden) return;
  if (e.key === "ArrowRight" || e.key === " ") turn(1); else if (e.key === "ArrowLeft") turn(-1);
});
let rz; addEventListener("resize", () => { if (!R) return; clearTimeout(rz); rz = setTimeout(() => showChapter(R.ci, { frac: R.page / R.pages }), 250); });

// contents + appearance
$("#rtoc").addEventListener("click", () => {
  const list = R.epub.toc.map((t) => ({ ...t, i: R.epub.chapterIndexForPath(t.path.split("#")[0]) })).filter((t) => t.i >= 0);
  const items = list.length ? list : R.epub.spine.map((_, i) => ({ title: `Section ${i + 1}`, i }));
  const cur = items.filter((t) => t.i <= R.ci).pop();
  $("#toclist").innerHTML = items.map((t, k) => `<button data-i="${t.i}" class="${t === cur ? "on" : ""}">${esc(t.title)}</button>`).join("");
  $("#toc").hidden = false;
});
$("#toclist").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b) { $("#toc").hidden = true; showChapter(Number(b.dataset.i)); } });
$("#rset").addEventListener("click", () => { applyChrome(); $("#settings").hidden = !$("#settings").hidden; });
document.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", () => b.closest(".sheet").hidden = true));
const relayout = () => { saveSettings(); applyChrome(); if (R) showChapter(R.ci, { frac: R.page / R.pages }); };
$("#themes").addEventListener("click", (e) => { const v = e.target.dataset.v; if (v) { settings.theme = v; relayout(); } });
$("#fonts").addEventListener("click", (e) => { const v = e.target.dataset.v; if (v) { settings.font = v; relayout(); } });
$("#fdown").addEventListener("click", () => { settings.size = Math.max(12, settings.size - 2); relayout(); });
$("#fup").addEventListener("click", () => { settings.size = Math.min(40, settings.size + 2); relayout(); });
$("#spacing").addEventListener("change", (e) => { settings.spacing = Number(e.target.value); relayout(); });
$("#margin").addEventListener("change", (e) => { settings.margin = Number(e.target.value); relayout(); });


/* ---------------- comic and PDF reader ---------------- */
let C = null, comicUrl = null;
const comicImage = $("#comicpage");
function comicProgress() {
 if (!C) return;
 $("#cslide").max = C.pages.length; $("#cslide").value = C.page + 1;
 $("#cprogress").textContent = `${C.page + 1} / ${C.pages.length}`;
 LS.set(`gr:c:${C.id}`, { page:C.page, o:(C.page+1)/C.pages.length, ts:Date.now() });
}
async function showComicPage() {
 if (!C || C.format === "pdf") return;
 if (comicUrl) URL.revokeObjectURL(comicUrl);
 const path = C.pages[C.page];
 const ext = path.split(".").pop().toLowerCase();
 comicUrl = URL.createObjectURL(await C.zip.blob_(path, ext === "png" ? "image/png" : ext === "webp" ? "image/webp" : "image/jpeg"));
 comicImage.src = comicUrl;
 comicProgress();
}
function turnComic(delta) {
 if (!C || C.format === "pdf") return;
 C.page = Math.max(0,Math.min(C.pages.length-1,C.page+(C.rtl?-delta:delta)));
 showComicPage();
}
async function openComic(id) {
 const rec = await DB.get("books",id); if (!rec) return;
 if (comicUrl) { URL.revokeObjectURL(comicUrl); comicUrl=null; }
 const saved=LS.get(`gr:c:${id}`,{});
 C={id,format:rec.format,rtl:!!rec.manga,page:saved.page||0,pages:[],zip:null};
 $("#ctitle").textContent=rec.title;
 $("#library").hidden=true; $("#comicreader").hidden=false;
 $("#cdir").textContent=C.rtl?"RTL":"LTR"; $("#cmode").textContent="Page";
 $("#pdfpage").hidden=true; comicImage.hidden=false; $("#comicbar").hidden=false;
 if(rec.format==="pdf") {
  C.pages=[0]; C.format="pdf"; comicImage.hidden=true; $("#comicbar").hidden=true;
  const url=URL.createObjectURL(rec.blob); comicUrl=url;
  $("#pdfpage").src=url; $("#pdfpage").hidden=false;
  $("#cprogress").textContent="PDF"; return;
 }
 C.zip=await Zip.open(rec.blob);
 C.pages=C.zip.names().filter(x=>/\.(jpe?g|png|webp|gif)$/i.test(x)&&!x.startsWith("__MACOSX/")).sort((x,y)=>x.localeCompare(y,undefined,{numeric:true,sensitivity:"base"}));
 C.page=Math.max(0,Math.min(C.pages.length-1,C.page));
 await showComicPage();
}
$("#cback").addEventListener("click",()=>{
 $("#comicreader").hidden=true; $("#library").hidden=false;
 comicImage.removeAttribute("src"); $("#pdfpage").removeAttribute("src");
 if(comicUrl) URL.revokeObjectURL(comicUrl); comicUrl=null; C=null; renderLibrary();
});
$("#cprev").addEventListener("click",()=>turnComic(-1));
$("#cnext").addEventListener("click",()=>turnComic(1));
$("#cslide").addEventListener("input",e=>{if(C&&C.format!=="pdf"){C.page=Number(e.target.value)-1;showComicPage();}});
$("#cdir").addEventListener("click",async()=>{
 if(!C)return; C.rtl=!C.rtl; $("#cdir").textContent=C.rtl?"RTL":"LTR";
 const rec=await DB.get("books",C.id); rec.manga=C.rtl; await DB.put("books",rec);
});
$("#cmode").addEventListener("click",()=>{
 const scroll=$("#comicstage").classList.toggle("scrollmode");
 $("#cmode").textContent=scroll?"Scroll":"Page";
});
comicImage.addEventListener("click",e=>{const x=e.offsetX/e.target.clientWidth;turnComic(x<.4?-1:x>.6?1:0);});

/* ---------------- audiobook player ---------------- */
const audio = $("#audio");
let A = null, aUrl = null, lastSave = 0;
const SPEEDS = [0.8, 1, 1.25, 1.5, 1.75, 2];
const fmt = (s) => { s = Math.max(0, Math.floor(s || 0)); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60; return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0"); };

async function openAudio(id) {
  const rec = await DB.get("audio", id);
  if (!rec) return;
  A = { id, rec };
  if (aUrl) URL.revokeObjectURL(aUrl);
  aUrl = URL.createObjectURL(rec.blob);
  audio.src = aUrl;
  const saved = progressOf("a", id) || { t: 0 };
  audio.playbackRate = LS.get("gr:speed", 1);
  $("#pspeed").textContent = audio.playbackRate.toFixed(2).replace(/0$/, "") + "×";
  audio.onloadedmetadata = () => { if (saved.t > 5 && saved.t < audio.duration - 5) audio.currentTime = saved.t; $("#pdur").textContent = fmt(audio.duration); };
  $("#ptitle").textContent = rec.title; $("#pauthor").textContent = rec.author;
  const cover = $("#pcover");
  cover.innerHTML = "";
  if (rec.cover) { const im = new Image(); im.src = URL.createObjectURL(rec.cover); cover.append(im); }
  else { const hue = [...rec.title].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7); cover.innerHTML = `<div class="gen" style="background:linear-gradient(160deg,hsl(${hue} 55% 38%),hsl(${(hue + 40) % 360} 70% 14%))">${esc(rec.title)}</div>`; }
  $("#library").hidden = true; $("#player").hidden = false;
  if ("mediaSession" in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({ title: rec.title, artist: rec.author || "GhostReader", artwork: rec.cover ? [{ src: URL.createObjectURL(rec.cover), sizes: "512x512" }] : [] });
    navigator.mediaSession.setActionHandler("play", () => audio.play());
    navigator.mediaSession.setActionHandler("pause", () => audio.pause());
    navigator.mediaSession.setActionHandler("seekbackward", () => (audio.currentTime -= 30));
    navigator.mediaSession.setActionHandler("seekforward", () => (audio.currentTime += 30));
  }
}
function saveAudio(force) {
  if (!A || !audio.duration) return;
  const now = Date.now();
  if (!force && now - lastSave < 3000) return;
  lastSave = now;
  LS.set(`gr:a:${A.id}`, { t: audio.currentTime, d: audio.duration, ts: now });
}
audio.addEventListener("timeupdate", () => { if (!audio.duration) return; $("#pcur").textContent = fmt(audio.currentTime); $("#pseek").value = Math.round(audio.currentTime / audio.duration * 1000); saveAudio(false); });
audio.addEventListener("pause", () => { $("#pplay").textContent = "▶"; saveAudio(true); });
audio.addEventListener("play", () => ($("#pplay").textContent = "❚❚"));
$("#pplay").addEventListener("click", () => (audio.paused ? audio.play() : audio.pause()));
$("#pseek").addEventListener("input", (e) => { if (audio.duration) audio.currentTime = Number(e.target.value) / 1000 * audio.duration; });
$("#pback30").addEventListener("click", () => (audio.currentTime = Math.max(0, audio.currentTime - 30)));
$("#pfwd30").addEventListener("click", () => (audio.currentTime = Math.min(audio.duration, audio.currentTime + 30)));
$("#pspeed").addEventListener("click", () => {
  const i = SPEEDS.indexOf(audio.playbackRate), n = SPEEDS[(i + 1) % SPEEDS.length];
  audio.playbackRate = n; LS.set("gr:speed", n); $("#pspeed").textContent = n.toFixed(2).replace(/0$/, "") + "×";
});
$("#pback").addEventListener("click", () => { saveAudio(true); audio.pause(); $("#player").hidden = true; $("#library").hidden = false; renderLibrary(); });
addEventListener("pagehide", () => { saveAudio(true); });

/* ---------------- start ---------------- */
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
renderLibrary();
