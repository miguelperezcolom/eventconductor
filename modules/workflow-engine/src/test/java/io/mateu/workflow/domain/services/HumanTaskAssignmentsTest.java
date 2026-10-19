package io.mateu.workflow.domain.services;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.TaskAssignment;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.template.Templates;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A USER_TASK's assignment rendered from the process — what the forms engine receives. */
class HumanTaskAssignmentsTest {

    static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    @AfterEach
    void resetClock() {
        EngineClock.reset();
    }

    private static Process process(Variable... variables) {
        return Process.builder().id("p-1").businessKey("order-7").workflowDefinitionId("expenses")
                .variables(List.of(variables)).build();
    }

    private static Step step(String assignmentJson) {
        return JsonSerializer.pojoFromJson("""
                {"id": "approve", "type": "USER_TASK", "name": "Approve", "formId": "approval",
                 "assignment": %s}""".formatted(assignmentJson), Step.class);
    }

    @Test
    void aTaskThatDeclaresNoAssignmentCarriesOnlyItsBusinessKey() {
        var step = JsonSerializer.pojoFromJson("""
                {"id": "approve", "type": "USER_TASK", "name": "Approve", "formId": "approval"}""", Step.class);

        var assignment = HumanTaskAssignments.resolve(step, process(), "se-1", null);

        assertThat(assignment.restricted()).isFalse();
        assertThat(assignment.businessKey()).isEqualTo("order-7");
        assertThat(assignment.priority()).isNull();
    }

    @Test
    void everyFieldIsRenderedFromTheProcessVariables() {
        EngineClock.set(Clock.fixed(NOW, ZoneId.of("UTC")));
        var step = step("""
                {"assignee": "${manager}",
                 "candidateUsers": ["alice", "${backup}"],
                 "candidateGroups": "${groups}",
                 "priority": "${amount > 1000 ? 90 : 40}",
                 "dueDate": "P3D",
                 "followUpDate": "${reviewOn}"}""");
        var process = process(
                new Variable("manager", "bob"),
                new Variable("backup", "carol"),
                new Variable("groups", "[\"finance\", \"/managers\"]"),
                new Variable("amount", "1500"),
                new Variable("reviewOn", "2026-10-20"));

        var assignment = HumanTaskAssignments.resolve(step, process, "se-1", null);

        assertThat(assignment.assignee()).isEqualTo("bob");
        assertThat(assignment.candidateUsers()).containsExactly("alice", "carol");
        assertThat(assignment.candidateGroups()).containsExactly("finance", "/managers");
        assertThat(assignment.priority()).isEqualTo(90);
        assertThat(assignment.dueDate()).isEqualTo("2026-10-22T08:00:00Z");
        assertThat(assignment.followUpDate()).isEqualTo("2026-10-20T00:00:00Z");
        assertThat(assignment.businessKey()).isEqualTo("order-7");
    }

    @Test
    void anIterationsItemIsInScope() {
        var step = step("""
                {"assignee": "${line.owner}", "candidateGroups": ["team-${line.team}"]}""");

        var assignment = HumanTaskAssignments.resolve(step, process(), "se-1",
                Map.of("line", Map.of("owner", "dave", "team", "a")));

        assertThat(assignment.assignee()).isEqualTo("dave");
        assertThat(assignment.candidateGroups()).containsExactly("team-a");
    }

    @Test
    void anUndefinedVariableRendersUnassignedRatherThanFailing() {
        var assignment = HumanTaskAssignments.resolve(step("{\"assignee\": \"${nobody}\"}"), process(), "se-1", null);

        assertThat(assignment.assignee()).isNull();
        assertThat(assignment.restricted()).isFalse();
    }

    @Test
    void namesComeAsAListAJsonArrayOrCommaSeparatedText() {
        assertThat(HumanTaskAssignments.names("a, b ,,c", "x")).containsExactly("a", "b", "c");
        assertThat(HumanTaskAssignments.names(" [\"a\", [\"b\"]] ", "x")).containsExactly("a", "b");
        assertThat(HumanTaskAssignments.names(List.of("a", List.of("b", "c")), "x")).containsExactly("a", "b", "c");
        assertThat(HumanTaskAssignments.names(null, "x")).isEmpty();
        assertThatThrownBy(() -> HumanTaskAssignments.names(Map.of("a", 1), "candidateUsers"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidateUsers");
        assertThatThrownBy(() -> HumanTaskAssignments.names("[oops", "candidateGroups"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidateGroups");
    }

    @Test
    void aPriorityIsAWholeNumberFromZeroToAHundred() {
        assertThat(HumanTaskAssignments.priority(70)).isEqualTo(70);
        assertThat(HumanTaskAssignments.priority(" 30 ")).isEqualTo(30);
        assertThat(HumanTaskAssignments.priority(" ")).isNull();
        assertThat(HumanTaskAssignments.priority(null)).isNull();
        assertThatThrownBy(() -> HumanTaskAssignments.priority("urgent")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HumanTaskAssignments.priority(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HumanTaskAssignments.priority(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HumanTaskAssignments.priority(2.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDateIsADurationFromNowOrAMoment() {
        EngineClock.set(Clock.fixed(NOW, ZoneId.of("Europe/Madrid")));
        assertThat(HumanTaskAssignments.moment("PT4H", NOW, "dueDate")).isEqualTo("2026-10-19T12:00:00Z");
        assertThat(HumanTaskAssignments.moment("2026-10-20T10:00:00+02:00", NOW, "dueDate")).isEqualTo("2026-10-20T08:00:00Z");
        // Without an offset, read in the engine's zone.
        assertThat(HumanTaskAssignments.moment("2026-10-20T10:00", NOW, "dueDate")).isEqualTo("2026-10-20T08:00:00Z");
        assertThat(HumanTaskAssignments.moment("2026-10-20", NOW, "dueDate")).isEqualTo("2026-10-19T22:00:00Z");
        assertThat(HumanTaskAssignments.moment(" ", NOW, "dueDate")).isNull();
        assertThatThrownBy(() -> HumanTaskAssignments.moment("next week", NOW, "followUpDate"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("followUpDate");
    }

    @Test
    void aTemplateThatCannotBeEvaluatedFails() {
        assertThatThrownBy(() -> HumanTaskAssignments.render(new TaskAssignment("${1 +}", null, null, null, null, null),
                Map.of(), NOW, null)).isInstanceOf(Templates.TemplateException.class);
    }
}
