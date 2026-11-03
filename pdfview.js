// PDF support: pdf.js is loaded on demand from cdnjs (cached by the service worker for offline use).
const PDFJS = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js";
const PDFJS_WORKER = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
let loading = null;
export function loadPdfjs() {
  if (window.pdfjsLib) return Promise.resolve(window.pdfjsLib);
  if (!loading) loading = new Promise((res, rej) => {
    const s = document.createElement("script");
    s.src = PDFJS;
    s.onload = () => { window.pdfjsLib.GlobalWorkerOptions.workerSrc = PDFJS_WORKER; res(window.pdfjsLib); };
    s.onerror = () => { loading = null; rej(new Error("Could not load the PDF engine. Connect to the internet once, then try again.")); };
    document.head.appendChild(s);
  });
  return loading;
}
export async function openPdf(blob) {
  const lib = await loadPdfjs();
  const data = new Uint8Array(await blob.arrayBuffer());
  return lib.getDocument({ data }).promise;
}
// Render one page (1-based) to a JPEG blob, `width` pixels wide.
export async function renderPage(doc, number, width) {
  const page = await doc.getPage(number);
  const base = page.getViewport({ scale: 1 });
  const viewport = page.getViewport({ scale: width / base.width });
  const canvas = document.createElement("canvas");
  canvas.width = Math.floor(viewport.width); canvas.height = Math.floor(viewport.height);
  await page.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
  const out = await new Promise((r) => canvas.toBlob(r, "image/jpeg", 0.88));
  canvas.width = canvas.height = 0;
  return out;
}
export async function pdfCover(blob) {
  const doc = await openPdf(blob);
  try { return await renderPage(doc, 1, 480); } finally { doc.destroy(); }
}
