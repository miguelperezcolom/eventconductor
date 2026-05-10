package io.mateu.workflow.mcp;

/**
 * MCP server components that want to contribute text to the AI agent's system prompt
 * implement this interface. The ia-agent-service collects these contributions via the
 * "system-context" MCP Prompt exposed by each server, then combines them with a local
 * base text to build the final system prompt for every request.
 */
public interface McpSystemContext {
    /** Returns the domain-specific context text that describes this component's capabilities. */
    String getSystemContext();
}
