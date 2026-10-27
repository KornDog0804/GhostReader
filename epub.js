import { Zip } from "./zip.js";

const MIME = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", svg: "image/svg+xml", webp: "image/webp", css: "text/css" };
const dirOf = (p) => p.includes("/") ? p.slice(0, p.lastIndexOf("/") + 1) : "";
function resolve(base, href) {
  href = decodeURIComponent(href.split("#")[0].split("?")[0]);
  const parts = (dirOf(base) + href).split("/"), out = [];
  for (const s of parts) { if (s === "..") out.pop(); else if (s && s !== ".") out.push(s); }
  return out.join("/");
}
const xml = (s) => new DOMParser().parseFromString(s, "application/xml");
const first = (doc, name) => doc.getElementsByTagNameNS("*", name)[0];
const all = (doc, name) => [...doc.getElementsByTagNameNS("*", name)];

export class Epub {
  static async open(blob) {
    const zip = await Zip.open(blob);
    const e = new Epub(zip);
    await e.#load();
    return e;
  }
  constructor(zip) { this.zip = zip; this.spine = []; this.toc = []; this.title = ""; this.author = ""; this.cache = new Map(); }
  async #load() {
    const container = xml(await this.zip.text("META-INF/container.xml"));
    this.opfPath = first(container, "rootfile").getAttribute("full-path");
    const opf = xml(await this.zip.text(this.opfPath));
    this.title = (first(opf, "title")?.textContent || "").trim();
    this.author = (first(opf, "creator")?.textContent || "").trim();
    const manifest = new Map();
    for (const it of all(opf, "item")) manifest.set(it.getAttribute("id"), { href: resolve(this.opfPath, it.getAttribute("href")), type: it.getAttribute("media-type"), props: it.getAttribute("properties") || "" });
    this.manifest = manifest;
    for (const ref of all(opf, "itemref")) {
      const m = manifest.get(ref.getAttribute("idref"));
      if (m && /html/.test(m.type || "") && ref.getAttribute("linear") !== "no") this.spine.push(m.href);
    }
    // cover
    let cover = null;
    for (const m of manifest.values()) if (m.props.includes("cover-image")) cover = m.href;
    if (!cover) {
      const meta = all(opf, "meta").find((x) => x.getAttribute("name") === "cover");
      if (meta) cover = manifest.get(meta.getAttribute("content"))?.href || null;
    }
    // EPUBs differ: some store the cover as a manifest image, others only
    // reference it from a cover XHTML page. Resolve both before giving up.
    if (!cover) {
      const candidates = [...manifest.values()].filter(m => /^image\/(jpeg|png|webp|gif)$/i.test(m.type || "") && this.zip.has(m.href));
      cover = candidates.find(m => /(^|[/_.-])cover([/_.-]|$)/i.test(m.href))?.href || null;
    }
    if (!cover) {
      const coverPage = [...manifest.values()].find(m => /(^|[/_.-])cover([/_.-]|$)/i.test(m.href) && /html/i.test(m.type || ""));
      if (coverPage && this.zip.has(coverPage.href)) {
        try {
          const page = new DOMParser().parseFromString(await this.zip.text(coverPage.href), "text/html");
          const src = page.querySelector("img[src]")?.getAttribute("src") || page.querySelector("image")?.getAttribute("href") || page.querySelector("image")?.getAttribute("xlink:href");
          if (src) cover = resolve(coverPage.href, src);
        } catch {}
      }
    }
    this.coverPath = cover && this.zip.has(cover) ? cover : null;
    // table of contents
    try {
      const nav = [...manifest.values()].find((m) => m.props.includes("nav"));
      const ncx = [...manifest.values()].find((m) => m.type === "application/x-dtbncx+xml");
      if (nav) {
        const d = new DOMParser().parseFromString(await this.zip.text(nav.href), "text/html");
        const root = d.querySelector("nav[epub\\:type='toc'], nav#toc, nav") || d;
        root.querySelectorAll("a[href]").forEach((a) => this.toc.push({ title: a.textContent.trim().replace(/\s+/g, " "), path: resolve(nav.href, a.getAttribute("href")) }));
      } else if (ncx) {
        const d = xml(await this.zip.text(ncx.href));
        all(d, "navPoint").forEach((n) => {
          const c = first(n, "content"), t = first(n, "text");
          if (c && t) this.toc.push({ title: t.textContent.trim(), path: resolve(ncx.href, c.getAttribute("src")) });
        });
      }
    } catch {}
    this.toc = this.toc.filter((t) => t.title);
  }
  async cover() { return this.coverPath ? this.zip.blob_(this.coverPath, MIME[this.coverPath.split(".").pop().toLowerCase()] || "image/jpeg") : null; }
  chapterIndexForPath(path) { return this.spine.indexOf(path); }
  chapterTitle(i) {
    const hit = this.toc.filter((t) => this.spine.indexOf(t.path) <= i && this.spine.indexOf(t.path) >= 0).pop();
    return hit ? hit.title : `Section ${i + 1}`;
  }
  async #url(path) {
    if (this.cache.has(path)) return this.cache.get(path);
    if (!this.zip.has(path)) return null;
    const ext = path.split(".").pop().toLowerCase();
    const u = URL.createObjectURL(await this.zip.blob_(path, MIME[ext] || ""));
    this.cache.set(path, u);
    return u;
  }
  /** Returns a ready-to-render HTML document string for chapter i, with images and styles inlined. */
  async chapterHtml(i) {
    const path = this.spine[i];
    const doc = new DOMParser().parseFromString(await this.zip.text(path), "text/html");
    for (const img of doc.querySelectorAll("img[src]")) { const u = await this.#url(resolve(path, img.getAttribute("src"))); if (u) img.setAttribute("src", u); }
    for (const im of doc.querySelectorAll("image")) {
      const attr = im.getAttribute("xlink:href") ? "xlink:href" : "href";
      const h = im.getAttribute(attr); if (!h) continue;
      const u = await this.#url(resolve(path, h)); if (u) im.setAttribute(attr, u);
    }
    let css = "";
    for (const l of doc.querySelectorAll("link[rel~='stylesheet'][href]")) {
      const p = resolve(path, l.getAttribute("href"));
      if (this.zip.has(p)) css += await this.zip.text(p) + "\n";
      l.remove();
    }
    doc.querySelectorAll("script").forEach((s) => s.remove());
    doc.querySelectorAll("a[href]").forEach((a) => {
      const h = a.getAttribute("href");
      if (/^https?:/i.test(h)) { a.setAttribute("target", "_blank"); return; }
      a.setAttribute("data-epub", resolve(path, h) + (h.includes("#") ? "#" + h.split("#")[1] : ""));
      a.setAttribute("href", "javascript:void(0)");
    });
    // The book's own styles stay out of the way of reading settings: keep them, but drop fixed colours/fonts later via our CSS.
    return { body: doc.body ? doc.body.innerHTML : "", css, path };
  }
  destroy() { for (const u of this.cache.values()) URL.revokeObjectURL(u); this.cache.clear(); }
}
