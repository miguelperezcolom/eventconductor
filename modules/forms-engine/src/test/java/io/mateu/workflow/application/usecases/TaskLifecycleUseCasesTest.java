package io.mateu.workflow.application.usecases;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskActionRefusedException;
import io.mateu.workflow.application.services.TaskAuthorization;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskNotFoundException;
import io.mateu.workflow.application.services.TaskTimeline;
import io.mateu.workflow.application.usecases.claimtask.ClaimTaskUseCase;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskCommand;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskUseCase;
import io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase;
import io.mateu.workflow.application.usecases.reassigntask.ReassignTaskUseCase;
import io.mateu.workflow.application.usecases.releasetask.ReleaseTaskUseCase;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskQuery;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import io.mateu.workflow.infra.out.memory.InMemoryFormExecutionRepository;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task's life after it is created — claimed, released, reassigned, escalated, completed — over the
 * in-memory store, with the rules of {@code TaskVisibility} and the form's own requirements applied.
 */
class TaskLifecycleUseCasesTest {

    static final TaskCaller ALICE = TaskCaller.of("alice", List.of(), false);
    static final TaskCaller CAROL = TaskCaller.of("carol", List.of("finance"), false);
    static final TaskCaller MALLORY = TaskCaller.of("mallory", List.of(), false);
    static final TaskCaller ADMIN = TaskCaller.of("root", List.of("task-admin"), true);

    InMemoryFormExecutionRepository repository;
    FormRepository formRepository;
    TaskAuthorization taskAuthorization;
    HumanTaskEvents events;
    StreamBridge streamBridge;
    TaskTimeline timeline;
    TaskInbox inbox;
    ClaimTaskUseCase claim;
    ReleaseTaskUseCase release;
    ReassignTaskUseCase reassign;
    EscalateTaskUseCase escalate;
    CompleteTaskUseCase complete;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFormExecutionRepository();
        formRepository = mock(FormRepository.class);
        when(formRepository.findById("approve")).thenReturn(Optional.of(new Form("approve", "Approve", "",
                List.of(new Field("decision", "Decision", FieldDataType.string, FieldStereotype.regular, false, "")))));
        taskAuthorization = mock(TaskAuthorization.class);
        events = mock(HumanTaskEvents.class);
        streamBridge = mock(StreamBridge.class);
        when(streamBridge.send(anyString(), any())).thenReturn(true);
        timeline = new TaskTimeline(streamBridge);
        inbox = new TaskInbox(repository, formRepository, taskAuthorization);
        claim = new ClaimTaskUseCase(repository, formRepository, inbox, taskAuthorization, events, timeline);
        release = new ReleaseTaskUseCase(repository, inbox, events, timeline);
        reassign = new ReassignTaskUseCase(repository, inbox, events, timeline);
        escalate = new EscalateTaskUseCase(repository, events);
        complete = new CompleteTaskUseCase(repository, formRepository, streamBridge, mock(FormsMetrics.class),
                taskAuthorization, events, inbox);
    }

    private FormExecution save(FormExecution.FormExecutionBuilder task) {
        var built = task.build();
        repository.save(built);
        return built;
    }

    private static FormExecution.FormExecutionBuilder task(String id) {
        return FormExecution.builder().id(id).formId("approve").processId("p-1").stepId("approve")
                .stepExecutionId("se-" + id).status(FormExecutionStatus.PENDING)
                .variables(List.of()).values(List.of());
    }

    private FormExecution stored(String id) {
        return repository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ claim

    @Test
    void aCandidateClaimsATaskOfferedToTheirGroup() {
        save(task("t1").candidateGroups(List.of("finance")).restricted(true));

        var claimed = claim.handle("t1", CAROL);

        assertThat(claimed.userId()).isEqualTo("carol");
        assertThat(claimed.status()).isEqualTo(FormExecutionStatus.ASSIGNED);
        verify(events).changed(claimed, HumanTaskChanged.CLAIMED);
        verify(streamBridge).send(eq("upstream"), eq(new TaskLogEmitted("se-t1", MessageType.Info,
                "form approve claimed by carol")));
    }

    @Test
    void aTaskNotOfferedToTheCallerIsNotFoundForThem() {
        save(task("t1").candidateGroups(List.of("finance")).restricted(true));

        assertThatThrownBy(() -> claim.handle("t1", MALLORY)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> claim.handle("missing", CAROL)).isInstanceOf(TaskNotFoundException.class);
        assertThat(stored("t1").isAssigned()).isFalse();
    }

    @Test
    void claimingDoesNotTakeWorkAwayFromAPerson() {
        save(task("t1").userId("alice").status(FormExecutionStatus.ASSIGNED));

        // Somebody else holds it: an admin may see it but not take it.
        assertThatThrownBy(() -> claim.handle("t1", ADMIN)).isInstanceOf(TaskActionRefusedException.class)
                .hasMessageContaining("alice holds it");
        // Its holder claiming again is a no-op.
        assertThat(claim.handle("t1", ALICE).userId()).isEqualTo("alice");
        verify(events, never()).changed(any(), eq(HumanTaskChanged.CLAIMED));
    }

    @Test
    void aClosedTaskCannotBeClaimed() {
        save(task("t1").status(FormExecutionStatus.COMPLETED));

        assertThatThrownBy(() -> claim.handle("t1", ADMIN)).isInstanceOf(TaskActionRefusedException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void theFormsRequirementsStillApply() {
        save(task("t1"));
        doThrow(new FlowAuthorizationDeniedException("no")).when(taskAuthorization)
                .refuseIfCallerMayNot(eq("claim"), any(), eq("t1"));

        assertThatThrownBy(() -> claim.handle("t1", ADMIN)).isInstanceOf(FlowAuthorizationDeniedException.class);
    }

    @Test
    void aTaskOnAFormTheCallerMayNotWorkOnIsNotFoundForThem() {
        save(task("t1"));
        when(taskAuthorization.enabled()).thenReturn(true);
        when(taskAuthorization.permittedFormIds()).thenReturn(Set.of("refund"));

        assertThatThrownBy(() -> inbox.get("t1", ADMIN)).isInstanceOf(TaskNotFoundException.class);
        assertThat(inbox.search(TaskQuery.open(), ADMIN).items()).isEmpty();
        assertThat(inbox.count(TaskQuery.open(), ADMIN)).isZero();
    }

    // ------------------------------------------------------------------ release

    @Test
    void theHolderReleasesATaskBackToItsCandidates() {
        save(task("t1").userId("carol").status(FormExecutionStatus.ASSIGNED).candidateGroups(List.of("finance"))
                .restricted(true));

        var released = release.handle("t1", CAROL);

        assertThat(released.isAssigned()).isFalse();
        assertThat(released.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(released.candidateGroups()).containsExactly("finance");
        verify(events).changed(released, HumanTaskChanged.RELEASED);
    }

    @Test
    void onlyTheHolderOrAnAdminReleases() {
        save(task("t1").userId("carol").status(FormExecutionStatus.ASSIGNED));

        assertThatThrownBy(() -> release.handle("t1", ALICE)).isInstanceOf(TaskNotFoundException.class);
        assertThat(release.handle("t1", ADMIN).isAssigned()).isFalse();
        assertThatThrownBy(() -> release.handle("t1", ADMIN)).isInstanceOf(TaskActionRefusedException.class);
    }

    // ------------------------------------------------------------------ reassign

    @Test
    void anAdminDecidesWhoATaskIsFor() {
        save(task("t1"));

        var toBob = reassign.handle("t1", ADMIN, " bob ", List.of("carol", " ", "carol"), List.of("/finance"));

        assertThat(toBob.userId()).isEqualTo("bob");
        assertThat(toBob.status()).isEqualTo(FormExecutionStatus.ASSIGNED);
        assertThat(toBob.candidateUsers()).containsExactly("carol");
        assertThat(toBob.candidateGroups()).containsExactly("finance");
        assertThat(toBob.isRestricted()).isTrue();
        verify(events).changed(toBob, HumanTaskChanged.ASSIGNED);

        var toEverybody = reassign.handle("t1", ADMIN, null, null, null);
        assertThat(toEverybody.isAssigned()).isFalse();
        assertThat(toEverybody.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(toEverybody.isRestricted()).isFalse();
    }

    @Test
    void onlyAnAdminReassigns() {
        save(task("t1"));

        assertThatThrownBy(() -> reassign.handle("t1", ALICE, "alice", null, null))
                .isInstanceOf(TaskActionRefusedException.class)
                .matches(e -> ((TaskActionRefusedException) e).forbidden());
        save(task("t2").status(FormExecutionStatus.COMPLETED));
        assertThatThrownBy(() -> reassign.handle("t2", ADMIN, "alice", null, null))
                .isInstanceOf(TaskActionRefusedException.class)
                .matches(e -> !((TaskActionRefusedException) e).forbidden());
    }

    // ------------------------------------------------------------------ escalate

    @Test
    void aReassigningEscalationGivesTheTaskToWhoeverItNamesNowOnce() {
        save(task("t1").userId("alice").status(FormExecutionStatus.ASSIGNED).restricted(true));
        var to = new HumanTaskAssignment(null, List.of(), List.of("/managers"), null, null, null, null);
        var escalation = new HumanTaskEscalationRequested("se-t1", "p-1", "approve", 1, "reassign", to, null,
                "2026-10-19T10:00:00Z");

        escalate.handle(escalation);

        var escalated = stored("t1");
        assertThat(escalated.isAssigned()).isFalse();
        assertThat(escalated.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(escalated.candidateGroups()).containsExactly("managers");
        assertThat(escalated.escalations()).isEqualTo(1);
        assertThat(escalated.escalatedAt()).isNotNull();
        verify(events).changed(escalated, HumanTaskChanged.ESCALATED);
        verify(events, never()).changed(any(), eq(HumanTaskChanged.ASSIGNED));

        // Redelivered: already at that level, nothing changes.
        escalate.handle(escalation);
        assertThat(stored("t1")).isEqualTo(escalated);
    }

    @Test
    void anEscalationToAPersonAssignsTheTaskToThem() {
        save(task("t1").candidateGroups(List.of("finance")).restricted(true));

        escalate.handle(new HumanTaskEscalationRequested("se-t1", "p-1", "approve", 2, "reassign",
                new HumanTaskAssignment("boss", List.of(), List.of(), null, null, null, null), null, "now"));

        var escalated = stored("t1");
        assertThat(escalated.userId()).isEqualTo("boss");
        assertThat(escalated.status()).isEqualTo(FormExecutionStatus.ASSIGNED);
        assertThat(escalated.candidateGroups()).isEmpty();
        assertThat(escalated.escalations()).isEqualTo(2);
        verify(events).changed(escalated, HumanTaskChanged.ASSIGNED);
    }

    @Test
    void aNotifyingEscalationIsRecordedAndAnnouncedAndAClosedTaskIsNeverReopened() {
        save(task("t1"));
        save(task("t2").stepExecutionId("se-t1").status(FormExecutionStatus.COMPLETED).userId("alice"));

        escalate.handle(new HumanTaskEscalationRequested("se-t1", "p-1", "approve", 1, "notify", null, null, "now"));
        escalate.handle(new HumanTaskEscalationRequested("se-t1", "p-1", "approve", 2, "reassign",
                new HumanTaskAssignment("boss", List.of(), List.of(), null, null, null, null), null, "now"));

        assertThat(stored("t1").escalations()).isEqualTo(2);
        assertThat(stored("t1").userId()).isEqualTo("boss");
        assertThat(stored("t2").status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(stored("t2").userId()).isEqualTo("alice");
        assertThat(stored("t2").escalations()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ complete

    @Test
    void onlyTheHolderCompletesForAPerson() {
        save(task("t1").userId("alice").status(FormExecutionStatus.ASSIGNED).restricted(true));

        assertThatThrownBy(() -> complete.handle(new CompleteTaskCommand("t1", List.of()), ADMIN))
                .isInstanceOf(TaskActionRefusedException.class).hasMessageContaining("(alice)");
        save(task("t2"));
        assertThatThrownBy(() -> complete.handle(new CompleteTaskCommand("t2", List.of()), ALICE))
                .isInstanceOf(TaskActionRefusedException.class).hasMessageContaining("claim it first");

        complete.handle(new CompleteTaskCommand("t1", List.of(new Value("decision", "OK"))), ALICE);

        var done = stored("t1");
        assertThat(done.status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(done.values()).containsExactly(new Value("decision", "OK"));
        verify(events).changed(done, HumanTaskChanged.COMPLETED);
    }

    // ------------------------------------------------------------------ timeline

    @Test
    void aTimelineLineThatCannotBeWrittenNeverFailsTheAction() {
        when(streamBridge.send(eq("upstream"), any())).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> timeline.log("se-1", "claimed")).doesNotThrowAnyException();
        assertThatCode(() -> timeline.log(null, "claimed")).doesNotThrowAnyException();
    }
}
