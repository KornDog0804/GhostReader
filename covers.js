// Cover lookup on Open Library, with the same careful matching as the Android app.
const norm = (s) => s.toLowerCase().replace(/[^a-z0-9 ]/g, " ").replace(/\s+/g, " ").trim();
const titleCase = (s) => /[a-z]/.test(s) || !/[A-Z]/.test(s) ? s : s.toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

/** Cleans a download-style file name into a title and author. */
export function parseName(raw) {
  let t = raw.replace(/\.[a-z0-9]{2,4}$/i, "").replace(/^\d+_/, "").replace(/_/g, " ").replace(/\s+/g, " ").trim();
  let author = "";
  const by = /^(.*?)\s+by\s+(.+)$/i.exec(t), dash = /^(.*?)\s+[-–—]\s+(.+)$/.exec(t);
  if (by) { t = by[1]; author = by[2]; } else if (dash) { t = dash[1]; author = dash[2]; }
  const junk = /\b(full\s+)?(audiobook|audio\s*book|unabridged|abridged|narrated\s+by|read\s+by|part\s*\d+).*$/i;
  author = author.replace(junk, "").replace(/\s*\(.*$/, "").replace(/^[\s,-]+|[\s,-]+$/g, "");
  t = t.replace(junk, "").replace(/\s+(?:a|an)\s+(?:litrpg|gamelit|novel|thriller|memoir|story|mystery|romance)\b.*$/i, "")
    .replace(/[,:\-–]?\s*(?:book|vol\.?|volume)\s*\d+.*$/i, "").replace(/^[\s,:-]+|[\s,:-]+$/g, "");
  if (author.split(" ").length > 5) author = "";
  return { title: titleCase(t || raw), author: titleCase(author) };
}

export async function fetchCover(title, author) {
  const hasAuthor = !!author && !/^unknown/i.test(author);
  if (!hasAuthor && title.split(" ").length < 3) return null;
  const tries = [...new Set([title, title.split(":")[0]].map((x) => x.trim()).filter((x) => x.length >= 3))];
  for (const t of tries) for (const withAuthor of hasAuthor ? [true, false] : [false]) {
    try {
      const url = "https://openlibrary.org/search.json?title=" + encodeURIComponent(t) + (withAuthor ? "&author=" + encodeURIComponent(author) : "") + "&limit=8&fields=cover_i,title,author_name";
      const docs = (await (await fetch(url)).json()).docs || [];
      for (const d of docs) {
        if (!d.cover_i) continue;
        const f = norm(d.title || ""), w = norm(t);
        if (!f || !(f === w || f.startsWith(w) || w.startsWith(f))) continue;
        if (hasAuthor) {
          const last = norm(author).split(" ").pop();
          if (last && !(d.author_name || []).some((n) => norm(n).includes(last))) continue;
        }
        const img = await fetch(`https://covers.openlibrary.org/b/id/${d.cover_i}-L.jpg?default=false`);
        if (!img.ok) continue;
        const blob = await img.blob();
        if (blob.size > 4000) return blob;
      }
    } catch { /* offline or blocked: just skip */ }
  }
  return null;
}

/** Second source: Google Books. Used when Open Library has nothing. */
export async function fetchGoogleCover(title, author) {
  const hasAuthor = !!author && !/^unknown/i.test(author);
  const q = `intitle:${title}` + (hasAuthor ? `+inauthor:${author}` : "");
  try {
    const r = await fetch("https://www.googleapis.com/books/v1/volumes?maxResults=8&printType=books&q=" + encodeURIComponent(q).replace(/%2B/g, "+"));
    const items = (await r.json()).items || [];
    const want = norm(title);
    for (const it of items) {
      const v = it.volumeInfo || {};
      const found = norm(v.title || "");
      if (!found || !(found === want || found.startsWith(want) || want.startsWith(found))) continue;
      if (hasAuthor) {
        const last = norm(author).split(" ").pop();
        if (last && !(v.authors || []).some((n) => norm(n).includes(last))) continue;
      }
      const link = v.imageLinks?.thumbnail || v.imageLinks?.smallThumbnail;
      if (!link) continue;
      const img = await fetch(link.replace(/^http:/, "https:").replace("zoom=1", "zoom=2").replace("&edge=curl", ""));
      if (!img.ok) continue;
      const blob = await img.blob();
      if (blob.size > 3000) return blob;
    }
  } catch { /* offline or blocked */ }
  return null;
}
