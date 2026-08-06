package io.mateu.workflow.infra.in.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules MCP tool surface, pinned as literals.
 *
 * <p>Same reasoning as the workflow engine's {@code WorkflowMcpToolSurfaceTest}: these tools are
 * part of the 1.0 contract and their callers are configured outside this repository, so a rename
 * has to fail here or it fails silently in someone else's assistant.
 */
class RulesMcpToolSurfaceTest {

    private static final String[] PUBLISHED_TOOLS = {
            "listRules",
            "getRule",
            "saveRule",
            "validateRule",
            "deleteRule",
            "evaluateRule",
            "importRulesFromGit",
    };

    @Test
    void thePublishedToolsAreExactlyTheOnesTheContractNames() {
        var names = Arrays.stream(RulesMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .toArray(String[]::new);

        assertThat(names).containsExactlyInAnyOrder(PUBLISHED_TOOLS);
    }

    @Test
    void everyToolIsPublicAndCarriesADescriptionAModelCanActOn() {
        for (var method : RulesMcpTools.class.getDeclaredMethods()) {
            var tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("%s is annotated @Tool but is not public", method.getName()).isTrue();
            assertThat(tool.description())
                    .as("%s has no description for a model to decide on", method.getName()).isNotBlank();
        }
    }
}
