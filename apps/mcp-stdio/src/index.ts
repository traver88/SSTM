import "dotenv/config";
import { createClient } from "@supabase/supabase-js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { plannerInstructions, registerTools } from "@kaoyan/mcp-core";

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
}

const supabaseUrl = requiredEnvironment("SUPABASE_URL");
const serviceRoleKey = requiredEnvironment("SUPABASE_SERVICE_ROLE_KEY");
const userId = requiredEnvironment("TARGET_USER_ID");

if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(userId)) {
  throw new Error("TARGET_USER_ID must be a valid UUID for a Supabase Auth user");
}

const supabase = createClient(supabaseUrl, serviceRoleKey, {
  auth: { autoRefreshToken: false, persistSession: false },
});
const server = new McpServer({ name: "kaoyan-ai-planner", version: "0.1.0" }, { instructions: plannerInstructions });
registerTools(server, supabase, userId);

const transport = new StdioServerTransport();
await server.connect(transport);
