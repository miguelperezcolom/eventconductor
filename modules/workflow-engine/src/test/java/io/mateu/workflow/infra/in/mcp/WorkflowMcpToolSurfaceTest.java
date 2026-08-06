package io.mateu.workflow.infra.in.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP tool surface, pinned.
 *
 * <p>{@code versioning.md} puts the MCP tools in the 1.0 stable contract, and their callers are
 * models and assistants configured outside this repository: a Claude Desktop entry, an agent
 * prompt, someone's script. Renaming or dropping a tool breaks all of them, and breaks nothing
 * here — the class still compiles, the engine still starts, and the tool simply stops existing.
 *
 * <p>So this asserts the published names as literals. It is deliberately a list that has to be
 * edited by hand: adding a tool is a MINOR change and editing this test says so out loud, while
 * removing or renaming one is a MAJOR change and the failing test is where that conversation
 * starts.
 *
 * <p>Descriptions are checked for presence, not wording. They are what a model reads to decide
 * whether to call a tool, so an empty one makes the tool unusable while leaving it "published".
 */
class WorkflowMcpToolSurfaceTest {

    private static final String[] PUBLISHED_TOOLS = {
            "listProcesses",
            "getProcessDetails",
            "findProcessByBusinessKey",
            "getProcessLogs",
            // Added with the CQRS process-index read model (opt-in via workflow.projection.enabled) —
            // adding tools is a MINOR change, pinned here by hand per this test's contract.
            "listInFlightProcesses",
            "countProcessesByStatus",
            "pauseProcess",
            "resumeProcess",
            "restartProcess",
            "retryProcess",
            "pauseWorkflow",
            "resumeWorkflow",
            "sendMessage",
            "getWorkflowAnalytics",
            "findBottleneck",
            "importWorkflowDefinitionsFromGit",
    };

    private static String[] toolNamesOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .sorted()
                .toArray(String[]::new);
    }

    @Test
    void thePublishedToolsAreExactlyTheOnesTheContractNames() {
        assertThat(toolNamesOf(WorkflowMcpTools.class))
                .containsExactlyInAnyOrder(PUBLISHED_TOOLS);
    }

    @Test
    void everyToolIsPublicAndCarriesADescriptionAModelCanActOn() {
        for (var method : WorkflowMcpTools.class.getDeclaredMethods()) {
            var tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("%s is annotated @Tool but is not public, so it is never exposed", method.getName())
                    .isTrue();
            assertThat(tool.description())
                    .as("%s has no description for a model to decide on", method.getName())
                    .isNotBlank();
        }
    }
}
