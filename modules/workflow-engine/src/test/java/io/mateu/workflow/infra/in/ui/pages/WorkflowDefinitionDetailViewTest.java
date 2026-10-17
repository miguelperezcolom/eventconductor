package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDefinitionDetailViewTest {

    /** A live step execution carrying its definition + step, as {@code findPendingOrRunning} returns. */
    private StepExecution se(String definitionId, String stepId) {
        var se = mock(StepExecution.class);
        when(se.getWorkflowDefinitionId()).thenReturn(definitionId);
        when(se.getStepId()).thenReturn(stepId);
        return se;
    }

    /** Same, but with a start time so it lands in a specific heatmap day-bucket. */
    private StepExecution se(String definitionId, String stepId, java.time.LocalDateTime startedAt) {
        var se = se(definitionId, stepId);
        when(se.getStartedAt()).thenReturn(startedAt);
        return se;
    }

    /** Only the step-execution repository is exercised; the rest go unused. */
    private WorkflowDefinitionDetailView view(StepExecutionRepository stepExecutions) {
        return new WorkflowDefinitionDetailView(null, stepExecutions, null, null, null, null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsEveryScheduleOfTheDefinitionIncludingCronFileEntries() {
        var cronFiles = new io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository();
        cronFiles.replace("/srv/defs", List.of(
                new io.mateu.workflow.domain.aggregates.CronFileSchedule("/srv/defs", 0, "report",
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 18 * * *", "UTC",
                                java.util.Map.of("region", "AMER"))),
                new io.mateu.workflow.domain.aggregates.CronFileSchedule("/srv/defs", 1, "other",
                        new io.mateu.workflow.domain.aggregates.CronSchedule("@daily"))));
        var def = new io.mateu.workflow.domain.aggregates.WorkflowDefinition("report", "Report", 1, null, false, 0,
                false, "0 0 3 * * *", 0, List.of())
                .withSchedules(List.of(new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 9 * * MON-FRI",
                        "Europe/Madrid", new java.util.LinkedHashMap<>(java.util.Map.of("region", "EMEA")))));
        var definitions = mock(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class);
        when(definitions.findAll()).thenReturn(List.of(def));
        var pauses = new io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository();
        var service = new io.mateu.workflow.application.services.ScheduleService(definitions, cronFiles, pauses,
                (java.util.function.Consumer<io.mateu.workflow.dtos.events.integration.ProcessCreationRequested>) c -> { });
        service.withClock(() -> java.time.Instant.parse("2026-10-14T10:00:00Z"));
        org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.services.ScheduleService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        var view = new WorkflowDefinitionDetailView(null, null, null, null, null, null, null, provider, null);
        var fileEntryId = "report~f" + io.mateu.workflow.domain.aggregates.Schedule.contentHash(
                new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 18 * * *", "UTC", java.util.Map.of("region", "AMER")));
        pauses.save(new io.mateu.workflow.domain.aggregates.SchedulePause(fileEntryId, java.time.Instant.EPOCH, "ops"));

        var rows = view.scheduleRows("report");

        assertThat(rows).extracting(ScheduleRow::declaredIn).containsExactly(
                "definition (cronExpression)", "definition (schedules[0])", "cron.yaml (directory) /srv/defs");
        assertThat(rows.get(1).zone()).isEqualTo("Europe/Madrid");
        assertThat(rows.get(1).variables()).isEqualTo("region=EMEA");
        assertThat(rows.get(1).nextFire()).isEqualTo("2026-10-15 09:00:00 +02:00 (Europe/Madrid)");
        assertThat(rows.get(2).variables()).isEqualTo("region=AMER");
        assertThat(rows.get(2).nextFire()).isEqualTo("2026-10-14 18:00:00 +00:00 (UTC)");
        assertThat(rows.get(0).variables()).isEqualTo("—");
        assertThat(rows.get(0).id()).isEqualTo("report~cron");
        // Each id opens the schedule's own page, relative to the definition's, whichever console.
        assertThat(rows.get(2).link()).isEqualTo("<a href=\"../schedules/" + fileEntryId + "\">" + fileEntryId + "</a>");
        assertThat(rows.get(2).status().message()).isEqualTo("Paused");
        assertThat(rows.get(1).status().message()).isEqualTo("Active");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsTheDefinitionsEventTriggersWithTheirTopicAndPause() {
        var def = new io.mateu.workflow.domain.aggregates.WorkflowDefinition("onboarding", "Onboarding", 1, null, false, 0,
                false, null, 0, List.of())
                .withTriggers(List.of(
                        new io.mateu.workflow.domain.aggregates.EventTrigger(null, "bookings", "com.acme.booking.created",
                                "data.channel == 'web'", null, new java.util.LinkedHashMap<>(java.util.Map.of("bookingId", "${data.bookingId}"))),
                        new io.mateu.workflow.domain.aggregates.EventTrigger("legacy", "legacy-bookings", "*", null, "b-${id}", null)));
        var definitions = mock(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class);
        when(definitions.findAll()).thenReturn(List.of(def));
        var properties = new io.mateu.workflow.infra.config.EventDestinationsProperties();
        var destination = new io.mateu.workflow.infra.config.EventDestinationsProperties.Destination();
        destination.setTopic("booking-events");
        properties.getDestinations().put("bookings", destination);
        var pauses = new io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository();
        pauses.save(new io.mateu.workflow.domain.aggregates.TriggerPause("onboarding/legacy", java.time.Instant.EPOCH, "ops"));
        var service = new io.mateu.workflow.application.services.EventTriggerService(definitions, pauses,
                new io.mateu.workflow.infra.out.memory.InMemoryEventReceiptRepository(), properties,
                new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository());
        org.springframework.beans.factory.ObjectProvider<io.mateu.workflow.application.services.EventTriggerService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        var view = new WorkflowDefinitionDetailView(null, null, null, null, null, null, null, null, provider);

        var rows = view.triggerRows("onboarding");

        assertThat(rows).extracting(io.mateu.workflow.infra.in.ui.pages.events.TriggerRow::trigger)
                .containsExactly("com.acme.booking.created", "legacy");
        assertThat(rows.get(0).destination()).isEqualTo("bookings → booking-events");
        assertThat(rows.get(0).variables()).isEqualTo("bookingId=${data.bookingId}");
        assertThat(rows.get(0).businessKey()).isEqualTo("<workflow>-evt-<trigger>-<event hash>");
        assertThat(rows.get(0).status().message()).isEqualTo("Active");
        assertThat(rows.get(1).destination()).isEqualTo("legacy-bookings → (no topic configured)");
        assertThat(rows.get(1).variables()).isEqualTo("data's top-level members");
        assertThat(rows.get(1).status().message()).isEqualTo("Paused");
        assertThat(view.triggerRows("other")).isEmpty();
    }

    @Test
    void countsLiveStepsOfThisDefinitionKeyedByStep() {
        var stepExecutions = mock(StepExecutionRepository.class);
        // One indexed query returns the whole system's live (PENDING/RUNNING) steps; the view filters
        // to this definition. Two live processes sit on "charge"; another definition is ignored.
        // Build the mocks first — nesting mock stubbing inside a when() confuses Mockito.
        var live = List.of(
                se("wd-1", "charge"),
                se("wd-1", "charge"),
                se("wd-2", "charge")); // excluded: other definition
        when(stepExecutions.findPendingOrRunning()).thenReturn(live);

        var counts = view(stepExecutions).liveProcessCountsByStep("wd-1");

        assertThat(counts).containsEntry("charge", 2);
        assertThat(counts).doesNotContainKey("start");
    }

    @Test
    void isEmptyWhenNoLiveStepSitsOnThisDefinition() {
        var stepExecutions = mock(StepExecutionRepository.class);
        var other = List.of(se("wd-2", "charge"));
        when(stepExecutions.findPendingOrRunning()).thenReturn(other);

        assertThat(view(stepExecutions).liveProcessCountsByStep("wd-1")).isEmpty();
    }

    @Test
    void bucketsStoppedTasksByDaysAgoAndFoldsThisDefinitionOnly() {
        var stepExecutions = mock(StepExecutionRepository.class);
        var now = java.time.LocalDateTime.now();
        var live = List.of(
                se("wd-1", "charge", now),                 // today   -> bucket 0
                se("wd-1", "charge", now.minusDays(5)),    // 5d ago  -> bucket 5
                se("wd-1", "charge", now.minusDays(1000)), // ancient -> folded into last bucket
                se("wd-2", "charge", now));                // excluded: other definition
        when(stepExecutions.findPendingOrRunning()).thenReturn(live);

        var heat = view(stepExecutions).stoppedTaskHeatByStep("wd-1");

        assertThat(heat).containsOnlyKeys("charge");
        var buckets = heat.get("charge");
        assertThat(buckets[0]).isEqualTo(1);
        assertThat(buckets[5]).isEqualTo(1);
        assertThat(buckets[WorkflowDefinitionDetailView.HEAT_WINDOW_DAYS - 1]).isEqualTo(1);
        // Summed over the whole window, the histogram reproduces this step's live count.
        assertThat(java.util.Arrays.stream(buckets).sum()).isEqualTo(3);
    }
}
