package io.mateu.workflow.dtos.humantasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HumanTaskAssignmentTest {

    @Test
    void namesAreTrimmedDeduplicatedAndBlankOnesDropped() {
        var assignment = new HumanTaskAssignment("  ", Arrays.asList(" alice ", "alice", null, ""),
                null, null, null, null, "bk");

        assertThat(assignment.assignee()).isNull();
        assertThat(assignment.candidateUsers()).containsExactly("alice");
        assertThat(assignment.candidateGroups()).isEmpty();
        assertThat(assignment.restricted()).isTrue();
        assertThat(assignment.priorityOrDefault()).isEqualTo(HumanTaskAssignment.DEFAULT_PRIORITY);
    }

    @Test
    void aTaskThatNamesNobodyIsNotRestricted() {
        assertThat(HumanTaskAssignment.none().restricted()).isFalse();
        assertThat(new HumanTaskAssignment(" bob ", null, null, 70, null, null, null).assignee()).isEqualTo("bob");
        assertThat(new HumanTaskAssignment(null, null, List.of("finance"), 70, null, null, null).restricted()).isTrue();
        assertThat(new HumanTaskAssignment(null, null, null, 70, null, null, null).priorityOrDefault()).isEqualTo(70);
    }

    @Test
    void itTravelsAsJsonAndAnUnreadableValueIsNone() {
        var assignment = new HumanTaskAssignment("alice", List.of("bob"), List.of("finance"), 80,
                "2026-10-20T09:00:00Z", null, "order-1");

        var json = assignment.toJson();

        assertThat(json).doesNotContain("followUpDate").doesNotContain("restricted");
        assertThat(HumanTaskAssignment.fromJson(json)).isEqualTo(assignment);
        assertThat(HumanTaskAssignment.fromJson("{\"assignee\":\"x\",\"unknown\":1}").assignee()).isEqualTo("x");
        assertThat(HumanTaskAssignment.fromJson("{oops")).isNull();
        assertThat(HumanTaskAssignment.fromJson(" ")).isNull();
        assertThat(HumanTaskAssignment.fromJson(null)).isNull();
    }

    @Test
    void aReassignmentKeepsTheTasksUrgencyAndDates() {
        var original = new HumanTaskAssignment("alice", List.of(), List.of(), 80, "due", "follow", "bk");
        var to = new HumanTaskAssignment(null, List.of(), List.of("managers"), 10, "other", null, null);

        var reassigned = original.reassignedTo(to);

        assertThat(reassigned.assignee()).isNull();
        assertThat(reassigned.candidateGroups()).containsExactly("managers");
        assertThat(reassigned.priority()).isEqualTo(80);
        assertThat(reassigned.dueDate()).isEqualTo("due");
        assertThat(reassigned.followUpDate()).isEqualTo("follow");
        assertThat(reassigned.businessKey()).isEqualTo("bk");
    }

    @Test
    void theEnginesOwnVariablesAreTheDoubleUnderscoredOnes() {
        assertThat(HumanTaskAssignment.isInternalVariable("__task")).isTrue();
        assertThat(HumanTaskAssignment.isInternalVariable("task")).isFalse();
        assertThat(HumanTaskAssignment.isInternalVariable(null)).isFalse();
    }

    @Test
    void anEscalationIsADomainEventKeyedByItsProcess() throws Exception {
        var escalation = new HumanTaskEscalationRequested("se-1", "p-1", "approve", 2,
                HumanTaskEscalationRequested.REASSIGN, HumanTaskAssignment.none(), null, "2026-10-19T10:00:00Z");

        assertThat(escalation.partitionKey()).isEqualTo("p-1");
        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString((DomainEvent) escalation);
        assertThat(json).contains("\"type\":\"human-task-escalation-requested\"");
        assertThat(mapper.readValue(json, DomainEvent.class)).isEqualTo(escalation);
        assertThat(HumanTaskEscalationRequested.NOTIFY).isEqualTo("notify");
        assertThat(HumanTaskEscalationRequested.ESCALATE_TO).isEqualTo("escalateTo");
    }

    @Test
    void aTaskChangeIsTypedByWhatHappened() {
        var changed = new HumanTaskChanged("t", "f", "F", "p", "s", "PENDING", List.of(), null, null);

        assertThat(changed.cloudEventType()).isEqualTo("io.eventconductor.task.changed");
        assertThat(changed.candidateUsers()).isEmpty();
        var escalated = new HumanTaskChanged("t", "f", "F", "p", "s", "PENDING", List.of(), null, null,
                HumanTaskChanged.ESCALATED, List.of(), List.of(), 50, null, 1, null);
        assertThat(escalated.cloudEventType()).isEqualTo(HumanTaskChanged.TYPE_PREFIX + "escalated");
    }
}
