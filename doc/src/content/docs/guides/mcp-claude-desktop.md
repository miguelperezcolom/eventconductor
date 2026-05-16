---
title: Connect Claude Desktop
description: Connect Claude Desktop to EventConductor in 5 minutes.
---

This guide shows you how to connect Claude Desktop to EventConductor so you can operate your workflows in natural language.

## Prerequisites

- Claude Desktop installed
- EventConductor services running

## 1. Start the services

Open three terminal windows:

```bash
# Terminal 1 — Orchestrator (workflow engine MCP server, port 8105)
cd apps/orchestrator-standalone-app
mvn spring-boot:run
```

```bash
# Terminal 2 — Forms engine MCP server (port 8106)
cd apps/forms-standalone-app
mvn spring-boot:run
```

```bash
# Terminal 3 — AI agent service (port 8095)
export ANTHROPIC_API_KEY=sk-ant-...
cd demo/ia-agent-service
mvn spring-boot:run
```

## 2. Configure Claude Desktop

Find your Claude Desktop config file:

- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

Add the EventConductor MCP servers:

```json
{
  "mcpServers": {
    "eventconductor": {
      "url": "http://localhost:8105/sse",
      "type": "sse"
    },
    "forms-engine": {
      "url": "http://localhost:8106/sse",
      "type": "sse"
    }
  }
}
```

## 3. Restart Claude Desktop

After saving the config, restart Claude Desktop. The EventConductor tools will appear automatically in the tool palette.

## 4. Try it out

Ask Claude Desktop anything about your workflows:

> *"Show me all running processes and retry the ones that are in error."*

> *"What user tasks are pending approval right now?"*

> *"Find the process for order B-1234 and tell me its current status."*

> *"Import the latest workflow definitions from Git."*

## Troubleshooting

**Tools not appearing in Claude Desktop**
- Verify the services are running: `curl http://localhost:8105/sse`
- Check the config file path and JSON syntax

**Agent not answering tool calls**
- Ensure `ANTHROPIC_API_KEY` is set and valid
- Check `demo/ia-agent-service` logs for errors

**Connection refused**
- Make sure ports 8105 and 8106 are not blocked by a firewall
- Verify the services started successfully (look for "Started" in logs)
