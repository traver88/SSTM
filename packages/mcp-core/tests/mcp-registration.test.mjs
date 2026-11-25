import assert from "node:assert/strict";
import test from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerTools } from "../dist/registerTools.js";

test("publishes all eleven tools through MCP listTools", async () => {
  const server = new McpServer({ name: "test-planner", version: "0.1.0" });
  const client = new Client({ name: "test-client", version: "0.1.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  registerTools(server, {}, "00000000-0000-4000-8000-000000000001");

  try {
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
    const result = await client.listTools();
    assert.equal(result.tools.length, 11);
    assert.ok(result.tools.some((tool) => tool.name === "log_study_session"));
    assert.ok(result.tools.every((tool) => typeof tool.inputSchema === "object"));

    const invalidCall = await client.callTool({ name: "upsert_exam_roadmap", arguments: {} });
    assert.equal(invalidCall.isError, true);
    assert.match(invalidCall.content[0].text, /Input validation error/);
  } finally {
    await client.close();
    await server.close();
  }
});
