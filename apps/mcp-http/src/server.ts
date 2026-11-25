import { randomUUID, webcrypto } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { Hono } from "hono";
import { registerTools } from "@kaoyan/mcp-core";

const WRITE_TOOLS = new Set([
  "upsert_profile_and_subjects",
  "upsert_exam_roadmap",
  "patch_exam_phase",
  "upsert_monthly_blueprint",
  "batch_upsert_plan_range",
  "patch_plan_tasks",
  "shift_plan_schedule",
  "log_study_session",
]);

type ApiKeyRecord = {
  id: string;
  user_id: string;
};

type ActiveSession = {
  userId: string;
  keyId: string;
  server: McpServer;
  transport: WebStandardStreamableHTTPServerTransport;
  touchedAt: number;
};

type RateBucket = {
  window: number;
  requests: number;
  writes: number;
};

export type HttpServerOptions = {
  supabase: SupabaseClient;
  readLimitPerMinute?: number;
  writeLimitPerMinute?: number;
  sessionIdleTimeoutMs?: number;
  maxSessions?: number;
  now?: () => number;
};

export function createHttpApp(options: HttpServerOptions): Hono {
  const app = new Hono();
  const sessions = new Map<string, ActiveSession>();
  const rateLimits = new Map<string, RateBucket>();
  const now = options.now ?? Date.now;
  const idleTimeoutMs = options.sessionIdleTimeoutMs ?? 60 * 60 * 1000;
  const maxSessions = options.maxSessions ?? 500;
  const readLimit = options.readLimitPerMinute ?? 60;
  const writeLimit = options.writeLimitPerMinute ?? 20;

  app.get("/healthz", (context) => context.json({ status: "ok" }));

  app.all("/mcp", async (context) => {
    const authorization = context.req.header("authorization");
    const match = authorization?.match(/^Bearer\s+(\S+)$/i);
    const apiKey = match?.[1];
    if (!apiKey) return context.json({ error: "unauthorized" }, 401, { "WWW-Authenticate": "Bearer" });

    const authenticated = await authenticateApiKey(options.supabase, apiKey);
    if (!authenticated) return context.json({ error: "unauthorized" }, 401, { "WWW-Authenticate": "Bearer" });

    const requestBody = await readRequestBody(context.req.raw);
    const writeRequest = isWriteRequest(requestBody);
    const rateLimit = consumeRateLimit(rateLimits, authenticated.user_id, now(), writeRequest, readLimit, writeLimit);
    if (!rateLimit.allowed) {
      context.header("Retry-After", String(rateLimit.retryAfterSeconds));
      return context.json({ error: "rate_limit_exceeded", retry_after_seconds: rateLimit.retryAfterSeconds }, 429);
    }

    expireIdleSessions(sessions, now(), idleTimeoutMs);
    const sessionId = context.req.header("mcp-session-id");
    if (sessionId) {
      const active = sessions.get(sessionId);
      if (!active || active.userId !== authenticated.user_id || active.keyId !== authenticated.id) {
        return context.json({ error: "invalid_session" }, 404);
      }
      active.touchedAt = now();
      return active.transport.handleRequest(context.req.raw);
    }

    if (context.req.method !== "POST" || !isInitializeRequest(requestBody)) {
      return context.json({ error: "session_initialization_required" }, 400);
    }
    if (sessions.size >= maxSessions) return context.json({ error: "server_busy" }, 503);

    const mcpServer = new McpServer({ name: "kaoyan-ai-planner", version: "0.1.0" });
    registerTools(mcpServer, options.supabase, authenticated.user_id);
    let initializedSessionId: string | undefined;
    let createdSession: ActiveSession | undefined;
    const transport = new WebStandardStreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      enableJsonResponse: true,
      onsessioninitialized: (id) => {
        initializedSessionId = id;
        createdSession = {
          userId: authenticated.user_id,
          keyId: authenticated.id,
          server: mcpServer,
          transport,
          touchedAt: now(),
        };
        sessions.set(id, createdSession);
      },
      onsessionclosed: async (id) => {
        const active = sessions.get(id);
        sessions.delete(id);
        if (active) await active.server.close();
      },
    });

    await mcpServer.connect(transport);
    try {
      return await transport.handleRequest(context.req.raw);
    } catch (error) {
      if (initializedSessionId) sessions.delete(initializedSessionId);
      await Promise.allSettled([transport.close(), mcpServer.close()]);
      throw error;
    } finally {
      if (!createdSession) await Promise.allSettled([transport.close(), mcpServer.close()]);
    }
  });

  return app;
}

export async function authenticateApiKey(supabase: SupabaseClient, apiKey: string): Promise<ApiKeyRecord | null> {
  if (!/^ky_live_[a-f0-9]{64}$/i.test(apiKey)) return null;
  const keyHash = await hashApiKey(apiKey);
  const { data, error } = await supabase.from("user_api_keys")
    .select("id,user_id")
    .eq("key_hash", keyHash)
    .is("revoked_at", null)
    .maybeSingle();
  if (error || !data) return null;

  await supabase.from("user_api_keys").update({ last_used_at: new Date().toISOString() })
    .eq("id", data.id)
    .is("revoked_at", null);
  return data as ApiKeyRecord;
}

export async function hashApiKey(apiKey: string): Promise<string> {
  const bytes = new TextEncoder().encode(apiKey);
  const digest = await webcrypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function consumeRateLimit(
  buckets: Map<string, RateBucket>,
  userId: string,
  timestamp: number,
  isWrite: boolean,
  readLimit: number,
  writeLimit: number,
): { allowed: true } | { allowed: false; retryAfterSeconds: number } {
  const window = Math.floor(timestamp / 60_000);
  const bucket = buckets.get(userId);
  const current = bucket?.window === window ? bucket : { window, requests: 0, writes: 0 };
  const retryAfterSeconds = Math.max(1, Math.ceil(((window + 1) * 60_000 - timestamp) / 1000));
  if (current.requests >= readLimit || (isWrite && current.writes >= writeLimit)) {
    return { allowed: false, retryAfterSeconds };
  }
  current.requests += 1;
  if (isWrite) current.writes += 1;
  buckets.set(userId, current);
  if (buckets.size > 10_000) {
    for (const [id, entry] of buckets) if (entry.window < window - 1) buckets.delete(id);
  }
  return { allowed: true };
}

function isWriteRequest(body: unknown): boolean {
  if (Array.isArray(body)) return body.some(isWriteRequest);
  if (!body || typeof body !== "object") return false;
  const message = body as { method?: unknown; params?: { name?: unknown } };
  return message.method === "tools/call" && typeof message.params?.name === "string" && WRITE_TOOLS.has(message.params.name);
}

function isInitializeRequest(body: unknown): boolean {
  return Boolean(body && typeof body === "object" && (body as { method?: unknown }).method === "initialize");
}

async function readRequestBody(request: Request): Promise<unknown> {
  if (request.method !== "POST") return null;
  try {
    return await request.clone().json();
  } catch {
    return null;
  }
}

function expireIdleSessions(sessions: Map<string, ActiveSession>, timestamp: number, idleTimeoutMs: number): void {
  for (const [sessionId, session] of sessions) {
    if (timestamp - session.touchedAt > idleTimeoutMs) {
      sessions.delete(sessionId);
      void Promise.allSettled([session.transport.close(), session.server.close()]);
    }
  }
}

export function createSupabaseClientFromEnvironment(environment: NodeJS.ProcessEnv = process.env): SupabaseClient {
  const url = requiredEnvironment(environment, "SUPABASE_URL");
  const serviceRoleKey = requiredEnvironment(environment, "SUPABASE_SERVICE_ROLE_KEY");
  return createClient(url, serviceRoleKey, { auth: { autoRefreshToken: false, persistSession: false } });
}

function requiredEnvironment(environment: NodeJS.ProcessEnv, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
}
