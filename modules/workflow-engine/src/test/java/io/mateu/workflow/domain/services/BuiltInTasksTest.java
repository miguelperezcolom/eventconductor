package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;

class BuiltInTasksTest {

    private static Step step(String json) {
        return pojoFromJson(json, Step.class);
    }

    @Test
    void builtInStepTypesDispatchTheirOwnTaskIds() {
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"USER_TASK\"}"))).isEqualTo("complete-form");
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"RULE\"}"))).isEqualTo("evaluate-rule");
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"HTTP_CALL\"}"))).isEqualTo("http-call@1");
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"LLM_CALL\"}"))).isEqualTo("llm-call@1");
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"AGENT\"}"))).isEqualTo("run-agent@1");
    }

    @Test
    void anActionDispatchesItsTaskContractOrNothing() {
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"ACTION\",\"task\":\"charge@2\"}"))).isEqualTo("charge@2");
        assertThat(BuiltInTasks.taskIdOf(step("{\"id\":\"s\",\"type\":\"ACTION\"}"))).isEmpty();
        assertThat(BuiltInTasks.taskIdOf(StepType.ACTION)).isNull();
    }

    @Test
    void aBuiltInTaskGoesToItsDefaultTopicUnlessTheStepNamesOne() {
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"HTTP_CALL\"}"))).isEqualTo("http-calls");
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"HTTP_CALL\",\"topic\":\" \"}"))).isEqualTo("http-calls");
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"HTTP_CALL\",\"topic\":\"egress\"}"))).isEqualTo("egress");
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"LLM_CALL\"}"))).isEqualTo("llm-calls");
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"RULE\"}"))).isNull();
    }

    @Test
    void anAgentAlwaysGoesToItsRuntimesTopic() {
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"AGENT\",\"topic\":\"elsewhere\"}"))).isEqualTo("agent-calls");
    }

    @Test
    void anyOtherStepKeepsItsOwnTopic() {
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"ACTION\",\"topic\":\"payments\"}"))).isEqualTo("payments");
        assertThat(BuiltInTasks.topicOf(step("{\"id\":\"s\",\"type\":\"ACTION\"}"))).isNull();
    }

    @Test
    void theRoutingSetsCoverEveryBuiltInTask() {
        assertThat(BuiltInTasks.taskIds()).containsExactlyInAnyOrder(
                "complete-form", "evaluate-rule", "http-call@1", "llm-call@1", "run-agent@1");
        assertThat(BuiltInTasks.defaultTopics()).containsExactlyInAnyOrder("http-calls", "llm-calls", "agent-calls");
    }
}
