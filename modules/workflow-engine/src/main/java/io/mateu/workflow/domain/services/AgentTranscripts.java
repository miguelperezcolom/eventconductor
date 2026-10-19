package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.StepExecution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads what an AGENT_CALL keeps on its own execution (see {@code AgentLoop.transcript}) for the views
 * that show it: the process detail's Agent tab, the REST and MCP step views.
 */
public final class AgentTranscripts {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SUMMARY_MESSAGE_CHARS = 280;

    /**
     * The agent's state, turns, tool calls, tokens and last message — null for an execution that is not
     * an agent, or an agent that has not recorded anything yet.
     */
    public static Map<String, Object> summary(StepExecution execution) {
        var document = document(execution);
        if (document == null) {
            return null;
        }
        var summary = new LinkedHashMap<String, Object>();
        for (var key : List.of("state", "turns", "toolCalls", "inputTokens", "outputTokens", "totalTokens")) {
            if (document.containsKey(key)) {
                summary.put(key, document.get(key));
            }
        }
        var entries = entries(document);
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if ("assistant".equals(entry.get("role")) && entry.get("content") != null
                    && !String.valueOf(entry.get("content")).isBlank()) {
                summary.put("lastMessage", AgentLoop.cap(String.valueOf(entry.get("content")), SUMMARY_MESSAGE_CHARS));
                break;
            }
        }
        return summary;
    }

    /** The transcript's entries, oldest first; empty when there is none. */
    public static List<Map<String, Object>> entries(StepExecution execution) {
        var document = document(execution);
        return document == null ? List.of() : entries(document);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> document) {
        return document.get("transcript") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
    }

    private static Map<String, Object> document(StepExecution execution) {
        if (execution == null || !execution.isAgent() || execution.getOutputJson() == null) {
            return null;
        }
        try {
            return JSON.readValue(execution.getOutputJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception unreadable) {
            return null;
        }
    }

    private AgentTranscripts() {
    }
}
