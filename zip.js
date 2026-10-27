// Minimal random-access ZIP reader. Works on huge files without loading them into memory.
export class Zip {
  constructor(blob) { this.blob = blob; this.entries = new Map(); }
  static async open(blob) {
    const z = new Zip(blob);
    await z.#index();
    return z;
  }
  async #u8(start, end) { return new Uint8Array(await this.blob.slice(start, end).arrayBuffer()); }
  async #index() {
    const size = this.blob.size;
    const tail = await this.#u8(Math.max(0, size - 65557), size);
    const tv = new DataView(tail.buffer);
    let e = -1;
    for (let i = tail.length - 22; i >= 0; i--) if (tv.getUint32(i, true) === 0x06054b50) { e = i; break; }
    if (e < 0) throw new Error("Not a ZIP file");
    let count = tv.getUint16(e + 10, true), cdSize = tv.getUint32(e + 12, true), cdOff = tv.getUint32(e + 16, true);
    if (cdOff === 0xffffffff || count === 0xffff) {
      // ZIP64: locator sits just before the end record.
      const loc = e - 20;
      if (loc >= 0 && tv.getUint32(loc, true) === 0x07064b50) {
        const off64 = Number(tv.getBigUint64(loc + 8, true));
        const rec = new DataView((await this.#u8(off64, off64 + 56)).buffer);
        count = Number(rec.getBigUint64(32, true));
        cdSize = Number(rec.getBigUint64(40, true));
        cdOff = Number(rec.getBigUint64(48, true));
      }
    }
    const cd = await this.#u8(cdOff, cdOff + cdSize);
    const dv = new DataView(cd.buffer);
    const dec = new TextDecoder();
    let p = 0;
    for (let i = 0; i < count && p + 46 <= cd.length; i++) {
      if (dv.getUint32(p, true) !== 0x02014b50) break;
      const flags = dv.getUint16(p + 8, true), method = dv.getUint16(p + 10, true);
      let csize = dv.getUint32(p + 20, true), usize = dv.getUint32(p + 24, true);
      const nlen = dv.getUint16(p + 28, true), elen = dv.getUint16(p + 30, true), clen = dv.getUint16(p + 32, true);
      let off = dv.getUint32(p + 42, true);
      const name = dec.decode(cd.subarray(p + 46, p + 46 + nlen));
      // ZIP64 extra field
      let q = p + 46 + nlen; const qEnd = q + elen;
      while (q + 4 <= qEnd) {
        const id = dv.getUint16(q, true), len = dv.getUint16(q + 2, true);
        if (id === 1) {
          let r = q + 4;
          if (usize === 0xffffffff) { usize = Number(dv.getBigUint64(r, true)); r += 8; }
          if (csize === 0xffffffff) { csize = Number(dv.getBigUint64(r, true)); r += 8; }
          if (off === 0xffffffff) { off = Number(dv.getBigUint64(r, true)); r += 8; }
        }
        q += 4 + len;
      }
      if (!name.endsWith("/")) this.entries.set(name, { name, method, csize, usize, off, flags });
      p += 46 + nlen + elen + clen;
    }
  }
  has(name) { return this.entries.has(name); }
  names() { return [...this.entries.keys()]; }
  async #raw(en) {
    const head = new DataView(await this.blob.slice(en.off, en.off + 30).arrayBuffer());
    const start = en.off + 30 + head.getUint16(26, true) + head.getUint16(28, true);
    return this.blob.slice(start, start + en.csize);
  }
  /** Returns the entry as a Blob (streamed, so big audio files are fine). */
  async blob_(name, type = "") {
    const en = this.entries.get(name);
    if (!en) throw new Error("Missing " + name);
    const raw = await this.#raw(en);
    if (en.method === 0) return raw.slice(0, raw.size, type);
    if (en.method !== 8) throw new Error("Unsupported ZIP compression");
    const stream = raw.stream().pipeThrough(new DecompressionStream("deflate-raw"));
    const out = await new Response(stream).blob();
    return type ? out.slice(0, out.size, type) : out;
  }
  async text(name) { return (await this.blob_(name)).text(); }
  async bytes(name) { return new Uint8Array(await (await this.blob_(name)).arrayBuffer()); }
}
