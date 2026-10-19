package io.mateu.workflow.infra.in.mcp;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The forms engine as an agent sees it.
 *
 * <p>Everything these tools return is a <em>summary</em> — the caller is a language model with a
 * context window, so a listing that inlined every field of every form would be both useless and
 * expensive. What the summaries leave out is therefore part of the contract, and so is what they
 * must not: a form execution's answers are the reason to ask for one.
 *
 * <p>The git import tool is the odd one out. It returns prose rather than a structure, because it
 * is the one tool whose result a human reads over the agent's shoulder, and it has to say something
 * useful in all three cases — imported, nothing found, and partly failed.
 */
class FormsMcpToolsTest {

    private FormRepository forms;
    private FormExecutionRepository executions;
    private ImportFormsFromGitUseCase gitImport;
    private FormsMcpTools tools;

    @BeforeEach
    void setUp() {
        forms = mock(FormRepository.class);
        executions = mock(FormExecutionRepository.class);
        gitImport = mock(ImportFormsFromGitUseCase.class);
        tools = new FormsMcpTools(forms, executions, gitImport, mock(io.mateu.workflow.application.services.TaskInbox.class),
                mock(io.mateu.workflow.application.services.TaskCallers.class));
    }

    @Test
    void the_form_listing_counts_the_fields_instead_of_inlining_them() {
        when(forms.findAll()).thenReturn(List.of(
                new Form("checkin", "Check in", "At the desk", List.of(field("a"), field("b"))),
                new Form("empty", "Empty", null, null)));

        var listed = tools.listForms();

        assertThat(listed).hasSize(2);
        assertThat(listed.getFirst().id()).isEqualTo("checkin");
        assertThat(listed.getFirst().fieldCount()).isEqualTo(2);
        // A definition with no fields at all is a real state — a form being written — and it must
        // not take the listing down with it.
        assertThat(listed.get(1).fieldCount()).isZero();
    }

    @Test
    void the_execution_listing_says_who_each_task_is_waiting_on() {
        when(executions.findAll()).thenReturn(List.of(
                execution("fe-1", FormExecutionStatus.PENDING, "alice", "reception")));

        var listed = tools.listFormExecutions();

        assertThat(listed).hasSize(1);
        var summary = listed.getFirst();
        assertThat(summary.id()).isEqualTo("fe-1");
        assertThat(summary.formId()).isEqualTo("checkin");
        assertThat(summary.processId()).isEqualTo("process-1");
        assertThat(summary.stepExecutionId()).isEqualTo("exec-1");
        assertThat(summary.status()).isEqualTo("PENDING");
        assertThat(summary.userId()).isEqualTo("alice");
        assertThat(summary.userGroup()).isEqualTo("reception");
    }

    @Test
    void the_detail_carries_the_answers_which_is_the_reason_to_ask_for_it() {
        when(executions.findById("fe-1")).thenReturn(Optional.of(
                io.mateu.workflow.domain.TaskFixtures.task("fe-1", "checkin", "process-1", "step-1", "exec-1",
                        FormExecutionStatus.COMPLETED, "alice", "reception",
                        List.of(new Variable("tenant", "acme")),
                        List.of(new Value("decision", "WALK")))));

        var detail = tools.getFormExecution("fe-1");

        assertThat(detail.status()).isEqualTo("COMPLETED");
        assertThat(detail.variables()).containsExactly("tenant=acme");
        assertThat(detail.values()).containsExactly("decision=WALK");
    }

    @Test
    void a_detail_for_an_execution_that_does_not_exist_says_so() {
        when(executions.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.getFormExecution("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void a_detail_with_nothing_filled_in_reads_as_empty_rather_than_failing() {
        when(executions.findById("fe-2")).thenReturn(Optional.of(
                io.mateu.workflow.domain.TaskFixtures.task("fe-2", "checkin", "process-1", "step-1", "exec-1",
                        FormExecutionStatus.PENDING, null, null, null, null)));

        var detail = tools.getFormExecution("fe-2");

        assertThat(detail.variables()).isEmpty();
        assertThat(detail.values()).isEmpty();
    }

    @Test
    void the_git_import_names_what_it_imported() {
        when(gitImport.handle()).thenReturn(new ImportFormsFromGitUseCase.ImportFormsResult(
                List.of("checkin", "checkout"), List.of(), List.of()));

        var answer = tools.importFormsFromGit();

        assertThat(answer).contains("Imported 2 form(s)").contains("checkin").contains("checkout");
        assertThat(answer).doesNotContain("Errors");
    }

    @Test
    void an_import_that_found_nothing_says_so_rather_than_answering_empty() {
        when(gitImport.handle()).thenReturn(new ImportFormsFromGitUseCase.ImportFormsResult(
                List.of(), List.of(), List.of()));

        assertThat(tools.importFormsFromGit()).contains("No new form definitions found");
    }

    @Test
    void an_import_that_partly_failed_reports_both_halves() {
        // Not one or the other: a repository where four of five files imported is the normal state
        // of a catalogue being edited, and an answer that mentioned only the failures would read as
        // a total failure.
        when(gitImport.handle()).thenReturn(new ImportFormsFromGitUseCase.ImportFormsResult(
                List.of("checkin"), List.of("broken.json: unexpected character"), List.of()));

        var answer = tools.importFormsFromGit();

        assertThat(answer).contains("Imported 1 form(s)").contains("checkin");
        assertThat(answer).contains("Errors (1)").contains("broken.json");
    }

    @Test
    void the_task_search_answers_as_the_caller_with_what_a_person_needs_to_pick_one() {
        var inbox = mock(io.mateu.workflow.application.services.TaskInbox.class);
        var callers = mock(io.mateu.workflow.application.services.TaskCallers.class);
        var carol = io.mateu.workflow.domain.TaskCaller.of("carol", List.of("finance"), false);
        when(callers.current()).thenReturn(carol);
        var due = java.time.LocalDateTime.of(2020, 1, 1, 9, 0);
        var task = FormExecution.builder().id("t-1").formId("approve").processId("p-1").businessKey("order-1")
                .status(FormExecutionStatus.PENDING).candidateGroups(List.of("finance")).priority(80).dueDate(due)
                .escalationLevel(1).variables(List.of()).values(List.of()).build();
        var queries = org.mockito.ArgumentCaptor.forClass(io.mateu.workflow.domain.TaskQuery.class);
        when(inbox.search(queries.capture(), org.mockito.ArgumentMatchers.eq(carol)))
                .thenReturn(new io.mateu.workflow.domain.TaskPage(List.of(task), 1, 0, 50));
        var withInbox = new FormsMcpTools(forms, executions, gitImport, inbox, callers);

        var found = withInbox.searchTasks("groups", true, 50, "p-1", "order-1");

        assertThat(found).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo("t-1");
            assertThat(summary.priority()).isEqualTo(80);
            assertThat(summary.overdue()).isTrue();
            assertThat(summary.dueDate()).isEqualTo("2020-01-01T09:00:00Z");
            assertThat(summary.candidateGroups()).containsExactly("finance");
            assertThat(summary.escalationLevel()).isEqualTo(1);
        });
        var query = queries.getValue();
        assertThat(query.scope()).isEqualTo(io.mateu.workflow.domain.TaskQuery.Scope.GROUPS);
        assertThat(query.overdue()).isTrue();
        assertThat(query.minPriority()).isEqualTo(50);
        assertThat(query.businessKey()).isEqualTo("order-1");

        when(inbox.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(carol)))
                .thenReturn(new io.mateu.workflow.domain.TaskPage(List.of(task.withDueDate(null)), 1, 0, 50));
        assertThat(withInbox.searchTasks(null, null, null, null, null)).singleElement()
                .satisfies(summary -> assertThat(summary.dueDate()).isNull());
    }

    @Test
    void the_system_context_tells_an_agent_what_it_is_looking_at() {
        assertThat(tools.getSystemContext()).contains("PENDING").contains("stepExecutionId");
    }

    private static Field field(String id) {
        return new Field(id, id, FieldDataType.string, FieldStereotype.regular, false, null);
    }

    private static FormExecution execution(String id, FormExecutionStatus status,
                                           String userId, String userGroup) {
        return io.mateu.workflow.domain.TaskFixtures.task(id, "checkin", "process-1", "step-1", "exec-1", status,
                userId, userGroup, List.of(), List.of());
    }
}
