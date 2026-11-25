import assert from "node:assert/strict";
import test from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createHttpApp, hashApiKey } from "../dist/server.js";

const activeKey = "ky_live_" + "a".repeat(64);
const activeKeyHash = await hashApiKey(activeKey);

function fakeSupabase(keyRows = [{ id: "key-1", user_id: "user-1", key_hash: activeKeyHash, revoked_at: null }]) {
  const state = { lookups: 0, lastUsedUpdates: 0 };
  const supabase = {
    from(table) {
      assert.equal(table, "user_api_keys");
      const filters = {};
      return {
        select() {
          return this;
        },
        update() {
          state.lastUsedUpdates += 1;
          return this;
        },
        eq(column, value) {
          filters[column] = value;
          return this;
        },
        is(column, value) {
          filters[column] = value;
          return this;
        },
        async maybeSingle() {
          state.lookups += 1;
          const row = keyRows.find((candidate) => candidate.key_hash === filters.key_hash && candidate.revoked_at === filters.revoked_at);
          return { data: row ?? null, error: null };
        },
      };
    },
  };
  return { supabase, state };
}

test("hashes and validates a bearer key before serving MCP", async () => {
  const { supabase, state } = fakeSupabase();
  const app = createHttpApp({ supabase });

  const health = await app.request("/healthz");
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { status: "ok" });

  const unauthorized = await app.request("/mcp", { method: "POST", body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize" }) });
  assert.equal(unauthorized.status, 401);
  assert.equal(state.lookups, 0);

  const client = new Client({ name: "http-test-client", version: "0.1.0" });
  let sessionId;
  const clientTransport = new StreamableHTTPClientTransport(new URL("http://localhost/mcp"), {
    requestInit: { headers: { Authorization: `Bearer ${activeKey}` } },
    fetch: async (input, init) => {
      const headers = new Headers(init?.headers);
      if (sessionId) headers.set("mcp-session-id", sessionId);
      const response = await app.request(new Request(input, { ...init, headers }));
      sessionId ??= response.headers.get("mcp-session-id") ?? undefined;
      return response;
    },
  });
  await client.connect(clientTransport);
  const tools = await client.listTools();
  assert.equal(tools.tools.length, 11);
  await client.close();
  assert.ok(state.lookups >= 1);
  assert.equal(state.lastUsedUpdates, state.lookups);
});

test("applies separate per-user read and write limits", async () => {
  const { supabase } = fakeSupabase();
  const app = createHttpApp({ supabase, readLimitPerMinute: 10, writeLimitPerMinute: 1, now: () => 60_000 });
  const call = (name) => app.request("/mcp", {
    method: "POST",
    headers: { Authorization: `Bearer ${activeKey}`, "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: {} } }),
  });

  const firstWrite = await call("log_study_session");
  assert.equal(firstWrite.status, 400);
  const secondWrite = await call("log_study_session");
  assert.equal(secondWrite.status, 429);
  assert.ok(Number(secondWrite.headers.get("retry-after")) > 0);
  const read = await call("get_monthly_plan");
  assert.equal(read.status, 400);
});

test("rejects malformed and revoked keys", async () => {
  const { supabase, state } = fakeSupabase([{ id: "key-1", user_id: "user-1", key_hash: activeKeyHash, revoked_at: "2026-01-01T00:00:00Z" }]);
  const app = createHttpApp({ supabase });
  const malformed = await app.request("/mcp", {
    method: "POST",
    headers: { Authorization: "Bearer ky_live_bad" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize" }),
  });
  const revoked = await app.request("/mcp", {
    method: "POST",
    headers: { Authorization: `Bearer ${activeKey}` },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize" }),
  });
  assert.equal(malformed.status, 401);
  assert.equal(revoked.status, 401);
  assert.equal(state.lookups, 1);
});
