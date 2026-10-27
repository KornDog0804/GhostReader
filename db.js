// Tiny IndexedDB wrapper: books and audiobooks live on the device so the app works offline.
const open = () => new Promise((res, rej) => {
  const r = indexedDB.open("ghostreader", 2);
  r.onupgradeneeded = () => {
    const names = r.result.objectStoreNames;
    if (!names.contains("books")) r.result.createObjectStore("books", { keyPath: "id", autoIncrement: true });
    if (!names.contains("audio")) r.result.createObjectStore("audio", { keyPath: "id", autoIncrement: true });
    // Covers are kept as plain data (not Blobs) so Safari can never lose them.
    if (!names.contains("covers")) r.result.createObjectStore("covers", { keyPath: "key" });
  };
  r.onsuccess = () => res(r.result);
  r.onerror = () => rej(r.error);
});
let dbp;
const db = () => (dbp ||= open());
const tx = async (store, mode, fn) => {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction(store, mode);
    const out = fn(t.objectStore(store));
    t.oncomplete = () => res(out.result);
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error);
  });
};
export const put = (store, item) => tx(store, "readwrite", (s) => s.put(item));
export const get = (store, id) => tx(store, "readonly", (s) => s.get(id));
export const del = (store, id) => tx(store, "readwrite", (s) => s.delete(id));
export const all = (store) => tx(store, "readonly", (s) => s.getAll());
