#!/usr/bin/env node
/**
 * mc-debug-mcp — stdio MCP server entrypoint.
 *
 * MCP client config (any client):
 *   { "mcpServers": { "minecraft": { "command": "node",
 *                                     "args": ["/path/to/mcp-server/dist/index.js"] } } }
 * Environment: MCB_PORT (default 25585), MCB_HOST (default 127.0.0.1).
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { registerTools } from "./tools.js";

const server = new McpServer({
  name: "mc-debug-mcp",
  version: "0.1.0",
});

registerTools(server);

await server.connect(new StdioServerTransport());
