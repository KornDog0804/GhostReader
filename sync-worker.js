// GhostReader sync Worker for Cloudflare Workers + R2.
// Bindings needed: BUCKET (an R2 bucket) and SYNC_KEY (a secret text variable, your passphrase).
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Allow-Methods": "GET, PUT, DELETE, OPTIONS",
  "Access-Control-Max-Age": "86400",
};
const KEY_OK = /^(?:(?:f|m|c)\/[0-9a-f]{40}(?:\.json)?|state\.json)$/;
const reply = (body, status = 200, extra = {}) => new Response(body, { status, headers: { ...CORS, ...extra } });

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return reply(null, 204);
    if (!env.SYNC_KEY || req.headers.get("Authorization") !== "Bearer " + env.SYNC_KEY) return reply("Unauthorized", 401);
    if (!env.BUCKET) return reply("R2 bucket binding named BUCKET is missing", 500);
    const url = new URL(req.url);
    if (url.pathname === "/ping") return reply(JSON.stringify({ ok: true }), 200, { "Content-Type": "application/json" });
    if (url.pathname === "/list") {
      const prefix = url.searchParams.get("prefix") || "m/";
      if (!/^(?:f|m|c)\/$/.test(prefix)) return reply("Bad prefix", 400);
      const keys = [];
      let cursor;
      do {
        const page = await env.BUCKET.list({ prefix, cursor, limit: 1000 });
        for (const o of page.objects) keys.push(o.key);
        cursor = page.truncated ? page.cursor : undefined;
      } while (cursor);
      return reply(JSON.stringify({ keys }), 200, { "Content-Type": "application/json" });
    }
    if (url.pathname.startsWith("/o/")) {
      const key = decodeURIComponent(url.pathname.slice(3));
      if (!KEY_OK.test(key)) return reply("Bad key", 400);
      if (req.method === "GET") {
        const obj = await env.BUCKET.get(key);
        if (!obj) return reply("Not found", 404);
        return reply(obj.body, 200, { "Content-Type": obj.httpMetadata?.contentType || "application/octet-stream" });
      }
      if (req.method === "PUT") {
        await env.BUCKET.put(key, req.body, { httpMetadata: { contentType: req.headers.get("Content-Type") || "application/octet-stream" } });
        return reply("ok");
      }
      if (req.method === "DELETE") { await env.BUCKET.delete(key); return reply("ok"); }
    }
    return reply("Not found", 404);
  },
};
