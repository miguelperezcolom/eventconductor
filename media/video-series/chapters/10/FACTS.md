# Chapter 10 — facts checked

| Claim | Source |
|---|---|
| LLM_CALL: `connection` is a name, provider and key in configuration, never in the definition | docs/guides/llm-calls.md:54-55, :67; workshop application.properties:33-43 |
| Prompt from variables; `responseSchema` validated; invalid → `LLM_INVALID_OUTPUT`, retryable, `retries` apply | llm-calls.md:78-91 |
| `output` maps `json.x` to variables; `usageVariable` = model + tokens | llm-calls.md:38, :61-63; recorded Variables tab (`classifyUsage` = fake-model, 120/40 tokens) |
| Model in the video = workshop stand-in (`/fake/llm`) — labelled on screen | workshop application.properties:34-43 |
| Real providers: `anthropic`, or `openai` for OpenAI and compatible servers; key from the environment | llm-calls.md:116, :126; workshop application.properties:37-39 |
| Sensitive variables stay out of prompts unless the step opts in (`allowSensitiveVariables`, 2.32) | docs/guides/sensitive-data-and-encryption.md:44; CL 2.32.0 (:276) |
| AGENT: ref, taskPrompt, input, outputVariable; ref checked against the catalogue at import; retries must be 0 | docs/guides/workflow-agents.md:26-37; CL 2.34.0 (:177-194) |
| AGENT_CALL removed in 2.34.0 (not shown) | CL :180-183 |
| Catalogue + runtime in the video = demo stand-in (rules) — labelled on screen | SA agents/DemoAgentCatalogue.java, DemoAgentRuntime.java; SA README |
| Agent result fields: category, needsStaff, actions, summary | DemoAgentRuntime.java (result map) |
| MCP server is opt-in (a dependency) | docs/guides/security-defaults.md:47-49; SA pom.xml:73-76, application.properties:62 |
| Scripted client, no model, real requests; no "start process" tool | SA scripts/mcp-demo.mjs:1-14; recorded output (58 tools) |
| Since: LLM_CALL 2.26, AGENT 2.34 (recap cards) | CL 2.26.0 (:466-471), 2.34.0 |
