package io.mateu.workflow.domain.aggregates;

import org.junit.jupiter.api.Test;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

/** Definition-level flow-authorization requirements parse from the definition JSON, and default to
 *  empty (open) when a file — or an older stored definition — omits them. */
class WorkflowDefinitionAuthorizationTest {

    @Test
    void parsesRequiredScopesAndRoles() {
        var def = pojoFromJson(
                "{\"name\":\"orders\",\"requiredScopes\":[\"orders:write\"],\"requiredRoles\":[\"operator\"],\"steps\":[]}",
                WorkflowDefinition.class);

        assertThat(def.requiredScopes()).containsExactly("orders:write");
        assertThat(def.requiredRoles()).containsExactly("operator");
    }

    @Test
    void defaultsToOpenWhenAbsent() {
        var def = pojoFromJson("{\"name\":\"orders\",\"steps\":[]}", WorkflowDefinition.class);

        assertThat(def.requiredScopes()).isEmpty();
        assertThat(def.requiredRoles()).isEmpty();
    }

    @Test
    void parsesStepLevelRequirements() {
        var def = pojoFromJson(
                "{\"name\":\"orders\",\"steps\":[{\"id\":\"approve\",\"type\":\"ACTION\",\"name\":\"Approve\","
                        + "\"requiredScopes\":[\"orders:approve\"]}]}",
                WorkflowDefinition.class);

        var step = def.steps().get(0);
        assertThat(step.requiredScopes()).containsExactly("orders:approve");
        assertThat(step.requiredRoles()).isEmpty();
    }
}
