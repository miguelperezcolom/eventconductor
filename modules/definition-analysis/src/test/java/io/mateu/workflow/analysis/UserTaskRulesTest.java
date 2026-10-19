package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserTaskRulesTest {

    private static final Set<String> IDS = Set.of("approve", "review", "end");

    private static List<String> check(Map<String, ?> assignment, List<? extends Map<String, ?>> escalation) {
        return UserTaskRules.violations("approve", "USER_TASK", assignment, escalation, false, false, IDS);
    }

    @Test
    void aFullAssignmentAndEscalationIsValid() {
        var assignment = Map.of(
                "assignee", "${manager}",
                "candidateUsers", List.of("alice", "${backup}"),
                "candidateGroups", "${groups}",
                "priority", 80,
                "dueDate", "P3D",
                "followUpDate", "2026-12-01T09:00:00");
        var escalation = List.of(
                Map.of("after", "PT48H", "action", "reassign", "to", Map.of("candidateGroups", List.of("managers")),
                        "publishEvent", Map.of("destination", "notifications", "type", "com.acme.escalated")),
                Map.of("after", "P3D", "action", "notify"),
                Map.of("after", "P5D", "escalateTo", "review"));

        assertThat(check(assignment, escalation)).isEmpty();
    }

    @Test
    void nothingDeclaredIsValid() {
        assertThat(check(null, null)).isEmpty();
        assertThat(check(null, List.of())).isEmpty();
    }

    @Test
    void onlyAUserTaskIsAssignedOrEscalated() {
        assertThat(UserTaskRules.violations("charge", "ACTION", Map.of("assignee", "x"),
                List.of(Map.of("after", "PT1H")), false, false, IDS))
                .containsExactly(
                        "Step 'charge' declares an assignment, which only a USER_TASK has.",
                        "Step 'charge' declares an escalation, which only a USER_TASK has.");
        assertThat(UserTaskRules.violations("charge", "ACTION", null, null, false, false, IDS)).isEmpty();
    }

    @Test
    void priorityIsAWholeNumberFromZeroToAHundredOrATemplate() {
        assertThat(check(Map.of("priority", 101), null)).singleElement().asString().contains("priority");
        assertThat(check(Map.of("priority", -1), null)).singleElement().asString().contains("priority");
        assertThat(check(Map.of("priority", 2.5), null)).singleElement().asString().contains("priority");
        assertThat(check(Map.of("priority", "150"), null)).singleElement().asString().contains("150");
        assertThat(check(Map.of("priority", "high"), null)).singleElement().asString().contains("'high'");
        assertThat(check(Map.of("priority", List.of(1)), null)).singleElement().asString().contains("priority");
        assertThat(check(Map.of("priority", "70"), null)).isEmpty();
        assertThat(check(Map.of("priority", "${amount > 1000 ? 90 : 50}"), null)).isEmpty();
    }

    @Test
    void datesAreADurationAMomentOrATemplate() {
        assertThat(check(Map.of("dueDate", "PT4H", "followUpDate", "2026-12-01"), null)).isEmpty();
        assertThat(check(Map.of("dueDate", "2026-12-01T10:00:00Z"), null)).isEmpty();
        assertThat(check(Map.of("dueDate", "${checkIn}"), null)).isEmpty();
        assertThat(check(Map.of("dueDate", "tomorrow"), null)).singleElement().asString()
                .contains("dueDate").contains("'tomorrow'");
        assertThat(check(Map.of("followUpDate", "-PT1H"), null)).singleElement().asString().contains("followUpDate");
        assertThat(check(Map.of("dueDate", 3), null)).singleElement().asString().contains("dueDate");
    }

    @Test
    void candidatesAreNamesOrATemplate() {
        assertThat(check(Map.of("candidateUsers", List.of("a", 1)), null)).singleElement().asString()
                .contains("candidateUsers");
        assertThat(check(Map.of("candidateGroups", 7), null)).singleElement().asString().contains("candidateGroups");
        assertThat(check(Map.of("assignee", List.of("a")), null)).singleElement().asString().contains("assignee");
    }

    @Test
    void anEscalationNeedsAPositiveDurationInFiringOrder() {
        var missing = new HashMap<String, Object>();
        missing.put("action", "notify");
        assertThat(check(null, List.of(missing))).singleElement().asString().contains("'after' is required");
        assertThat(check(null, List.of(Map.of("after", "soon")))).singleElement().asString().contains("'soon'");
        assertThat(check(null, List.of(Map.of("after", "PT0S")))).singleElement().asString().contains("positive");
        assertThat(check(null, List.of(Map.of("after", "P2D"), Map.of("after", "PT1H"))))
                .singleElement().asString().contains("order they fire");
    }

    @Test
    void anEscalationIsAnObject() {
        var entries = new ArrayList<Map<String, ?>>();
        entries.add(null);
        assertThat(check(null, entries)).singleElement().asString().contains("is an object");
    }

    @Test
    void theActionMustBeOneOfThree() {
        assertThat(check(null, List.of(Map.of("after", "PT1H", "action", "shout"))))
                .singleElement().asString().contains("'shout'");
    }

    @Test
    void aReassignNeedsSomebodyToReassignTo() {
        assertThat(check(null, List.of(Map.of("after", "PT1H", "action", "reassign"))))
                .singleElement().asString().contains("needs a 'to'");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "action", "reassign", "to", Map.of("candidateGroups", List.of())))))
                .singleElement().asString().contains("needs a 'to'");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "to", Map.of("assignee", " ")))))
                .singleElement().asString().contains("needs a 'to'");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "to", Map.of("candidateUsers", List.of("a", 2))))))
                .singleElement().asString().contains("to.candidateUsers");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "to", "managers"))))
                .singleElement().asString().contains("needs a 'to'");
    }

    @Test
    void toAndEscalateToBelongToTheirActions() {
        assertThat(check(null, List.of(Map.of("after", "PT1H", "action", "notify", "to", Map.of("assignee", "bob")))))
                .containsExactly("Step 'approve' escalation[0]: 'to' is only read by a reassign.");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "action", "notify", "escalateTo", "review"))))
                .containsExactly("Step 'approve' escalation[0]: 'escalateTo' is only read by an escalateTo action.");
    }

    @Test
    void anEscalateToNamesAnotherKnownStepAndComesLastAndOnlyOnce() {
        assertThat(check(null, List.of(Map.of("after", "PT1H", "action", "escalateTo"))))
                .singleElement().asString().contains("needs the id");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "escalateTo", "approve"))))
                .singleElement().asString().contains("itself");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "escalateTo", "nowhere"))))
                .singleElement().asString().contains("unknown step 'nowhere'");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "escalateTo", "review"), Map.of("after", "PT2H"))))
                .singleElement().asString().contains("must be the last");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "escalateTo", "review"),
                Map.of("after", "PT2H", "action", "escalate", "escalateTo", "end"))))
                .contains("Step 'approve' escalation: only one entry may escalateTo.");
    }

    @Test
    void aForEachTaskCannotEscalateTo() {
        assertThat(UserTaskRules.violations("approve", "USER_TASK", null,
                List.of(Map.of("after", "PT1H", "escalateTo", "review")), true, false, IDS))
                .singleElement().asString().contains("forEach");
    }

    @Test
    void escalationsDoNotMixWithADeadline() {
        assertThat(UserTaskRules.violations("approve", "USER_TASK", null,
                List.of(Map.of("after", "PT1H")), false, true, IDS))
                .singleElement().asString().contains("deadline");
    }

    @Test
    void aPublishEventNeedsADestinationAndAType() {
        assertThat(check(null, List.of(Map.of("after", "PT1H", "publishEvent", Map.of("type", "x")))))
                .singleElement().asString().contains("publishEvent");
        assertThat(check(null, List.of(Map.of("after", "PT1H", "publishEvent", "x"))))
                .singleElement().asString().contains("publishEvent");
    }

    @Test
    void anUnknownStepSetSkipsTheReferenceCheck() {
        assertThat(UserTaskRules.violations("approve", "USER_TASK", null,
                List.of(Map.of("after", "PT1H", "escalateTo", "anywhere")), false, false, null)).isEmpty();
    }

    @Test
    void theActionIsReadFromWhatTheEntryDeclares() {
        assertThat(UserTaskRules.actionOf(Map.of("escalateTo", "x"))).isEqualTo(UserTaskRules.ESCALATE_TO);
        assertThat(UserTaskRules.actionOf(Map.of("to", Map.of()))).isEqualTo(UserTaskRules.REASSIGN);
        assertThat(UserTaskRules.actionOf(Map.of())).isEqualTo(UserTaskRules.NOTIFY);
        assertThat(UserTaskRules.actionOf(Map.of("action", "REASSIGN"))).isEqualTo(UserTaskRules.REASSIGN);
        assertThat(UserTaskRules.actionOf(Map.of("action", "escalateTo"))).isEqualTo(UserTaskRules.ESCALATE_TO);
        assertThat(UserTaskRules.actionOf(Map.of("action", " "))).isEqualTo(UserTaskRules.NOTIFY);
    }

    @Test
    void durationsAndMomentsAreReadAsIso() {
        assertThat(UserTaskRules.parseDuration("P2D")).isEqualTo(Duration.ofDays(2));
        assertThat(UserTaskRules.parseDuration(" pt30m ")).isEqualTo(Duration.ofMinutes(30));
        assertThat(UserTaskRules.parseDuration(null)).isNull();
        assertThat(UserTaskRules.parseDuration("")).isNull();
        assertThat(UserTaskRules.parseDuration("3 days")).isNull();
        assertThat(UserTaskRules.isIsoMoment("2026-01-01")).isTrue();
        assertThat(UserTaskRules.isIsoMoment("2026-01-01T10:00")).isTrue();
        assertThat(UserTaskRules.isIsoMoment("2026-01-01T10:00:00+02:00")).isTrue();
        assertThat(UserTaskRules.isIsoMoment("soon")).isFalse();
        assertThat(UserTaskRules.isIsoMoment(null)).isFalse();
    }
}
