import "dotenv/config";
import { serve } from "@hono/node-server";
import { createHttpApp, createSupabaseClientFromEnvironment } from "./server.js";

const port = Number(process.env.PORT ?? "8787");
const hostname = process.env.HOST ?? "127.0.0.1";
if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error("PORT must be between 1 and 65535");

const app = createHttpApp({
  supabase: createSupabaseClientFromEnvironment(),
  readLimitPerMinute: positiveInteger(process.env.MCP_READ_LIMIT_PER_MINUTE, 60),
  writeLimitPerMinute: positiveInteger(process.env.MCP_WRITE_LIMIT_PER_MINUTE, 20),
  sessionIdleTimeoutMs: positiveInteger(process.env.MCP_SESSION_IDLE_TIMEOUT_MS, 3_600_000),
  maxSessions: positiveInteger(process.env.MCP_MAX_SESSIONS, 500),
});

serve({ fetch: app.fetch, port, hostname }, (info) => {
  console.info(`研途智排 MCP HTTP listening on ${info.address}:${info.port}`);
});

function positiveInteger(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1) throw new Error("MCP limits must be positive integers");
  return parsed;
}
