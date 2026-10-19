package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskPage;
import io.mateu.workflow.domain.TaskQuery;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.infra.out.memory.InMemoryFormExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task inbox in SQL, on a database the forms migrations built and the mappings were validated
 * against — held to {@code TaskVisibility} by asking the JPA repository and the in-memory one (which
 * applies the class itself) the same questions over the same tasks, for every kind of caller, scope,
 * filter and order, and requiring the same answers in the same order.
 */
abstract class TaskInboxQueryContract {

    static final LocalDateTime NOW = TaskInboxOrder.nowUtc().truncatedTo(ChronoUnit.SECONDS);

    static final TaskCaller ANONYMOUS = TaskCaller.anonymous();
    static final TaskCaller ALICE = TaskCaller.of("alice", List.of(), false);
    static final TaskCaller BOB = TaskCaller.of("bob", List.of(), false);
    static final TaskCaller CAROL = TaskCaller.of("carol", List.of("finance"), false);
    static final TaskCaller ERIN = TaskCaller.of("erin", List.of("/finance", "ops"), false);
    static final TaskCaller ADMIN = TaskCaller.of("root", List.of("task-admin"), true);
    static final List<TaskCaller> CALLERS = List.of(ANONYMOUS, ALICE, BOB, CAROL, ERIN, ADMIN);

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    FormExecutionDBRepository db;
    InMemoryFormExecutionRepository memory;

    abstract DataSource newDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void open() {
        dataSource = newDatabase();
        FormsJpaTestContext.migrate(dataSource);
        // Opens only if every mapping matches a migrated column (hbm2ddl=validate).
        context = FormsJpaTestContext.open(dataSource);
        db = new FormExecutionDBRepository(context.getBean(FormExecutionEntityRepository.class));
        memory = new InMemoryFormExecutionRepository();

        save(task("t-open").formId("approve").processId("p-1").businessKey("bk-1").priority(50)
                .createdAt(NOW.minusDays(5)).restricted(false).build());
        save(task("t-alice").status(FormExecutionStatus.ASSIGNED).userId("alice").priority(90)
                .dueDate(NOW.minusHours(1)).createdAt(NOW.minusDays(4)).processId("p-2").restricted(true).build());
        save(task("t-bob-held").status(FormExecutionStatus.ASSIGNED).userId("bob").priority(10)
                .createdAt(NOW.minusDays(3)).restricted(false).build());
        save(task("t-cand-user").candidateUsers(List.of("carol")).priority(70).dueDate(NOW.plusDays(2))
                .createdAt(NOW.minusDays(2)).restricted(true).build());
        save(task("t-cand-group").formId("refund").candidateGroups(List.of("finance")).priority(70)
                .dueDate(NOW.plusDays(1)).createdAt(NOW.minusDays(1)).businessKey("bk-2").restricted(true).build());
        save(task("t-group-held").status(FormExecutionStatus.ASSIGNED).userId("dave")
                .candidateGroups(List.of("finance")).priority(60).createdAt(NOW.minusHours(5)).restricted(true).build());
        save(task("t-released").priority(40).createdAt(NOW.minusHours(4)).restricted(true).build());
        save(task("t-done").status(FormExecutionStatus.COMPLETED).userId("alice").priority(30)
                .dueDate(NOW.minusDays(3)).createdAt(NOW.minusHours(3)).restricted(false).build());
        // A task from before assignment existed: every new column null.
        new JdbcTemplate(dataSource).update("""
                insert into form_execution_entity (id, form_id, process_id, step_id, step_execution_id, variables,
                    "values", status, user_id, user_group)
                values ('t-legacy', 'approve', 'p-9', 's1', 'se-t-legacy', '[]', '[]', 'PENDING', null, null)
                """);
        memory.save(task("t-legacy").formId("approve").processId("p-9").build());
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
        if (dataSource != null) {
            dispose(dataSource);
        }
    }

    private static FormExecution.FormExecutionBuilder task(String id) {
        return FormExecution.builder().id(id).formId("approve").processId("p-" + id).stepId("s1")
                .stepExecutionId("se-" + id).status(FormExecutionStatus.PENDING)
                .variables(List.of(new Variable("amount", "10"))).values(List.of());
    }

    private void save(FormExecution task) {
        db.save(task);
        memory.save(task);
    }

    private static List<String> ids(TaskPage page) {
        return page.items().stream().map(FormExecution::id).toList();
    }

    private List<String> search(TaskQuery query, TaskCaller caller) {
        var fromDb = ids(db.search(query, caller, null));
        assertThat(fromDb).as("SQL vs TaskVisibility for %s, %s", caller, query)
                .isEqualTo(ids(memory.search(query, caller, null)));
        return fromDb;
    }

    @Test
    void theSqlAnswersWhatTaskVisibilityAnswersForEveryCallerScopeAndOrder() {
        var queries = new ArrayList<TaskQuery>();
        for (var scope : TaskQuery.Scope.values()) {
            for (var sort : TaskQuery.Sort.values()) {
                queries.add(new TaskQuery(scope, null, null, null, null, null, null, null, sort, 0, 50));
                queries.add(new TaskQuery(scope, List.of(FormExecutionStatus.COMPLETED, FormExecutionStatus.PENDING,
                        FormExecutionStatus.ASSIGNED), null, null, null, null, null, null, sort, 0, 50));
            }
        }
        queries.add(new TaskQuery(null, null, 60, null, null, null, null, null, null, 0, 50));
        queries.add(new TaskQuery(null, null, null, true, null, null, null, null, null, 0, 50));
        queries.add(new TaskQuery(null, null, null, false, null, null, null, null, null, 0, 50));
        queries.add(new TaskQuery(null, null, null, null, NOW.plusDays(1).plusHours(1), null, null, null, null, 0, 50));
        queries.add(new TaskQuery(null, null, null, null, null, "p-1", null, null, null, 0, 50));
        queries.add(new TaskQuery(null, null, null, null, null, null, "bk-2", null, null, 0, 50));
        queries.add(new TaskQuery(null, null, null, null, null, null, null, "refund", null, 0, 50));
        queries.add(new TaskQuery(null, null, null, null, null, null, null, null, null, 1, 2));
        for (var caller : CALLERS) {
            for (var query : queries) {
                search(query, caller);
            }
        }
    }

    @Test
    void aTaskThatNamesNobodyIsAnybodysAndOneThatNamesSomeoneIsTheirs() {
        assertThat(search(TaskQuery.open(), ANONYMOUS)).containsExactlyInAnyOrder("t-open", "t-legacy");
        // Most urgent first, then soonest due, then oldest; a legacy row has the default priority (50)
        // and an unknown creation, which reads as older than any known one.
        assertThat(search(TaskQuery.open(), ALICE)).containsExactly("t-alice", "t-legacy", "t-open");
        assertThat(search(TaskQuery.open(), BOB)).containsExactly("t-legacy", "t-open", "t-bob-held");
        assertThat(search(TaskQuery.open(), CAROL))
                .containsExactly("t-cand-group", "t-cand-user", "t-legacy", "t-open");
        assertThat(search(TaskQuery.open(), ERIN)).containsExactly("t-cand-group", "t-legacy", "t-open");
        assertThat(search(TaskQuery.open(), ADMIN)).containsExactly("t-alice", "t-cand-group", "t-cand-user",
                "t-group-held", "t-legacy", "t-open", "t-released", "t-bob-held");
    }

    @Test
    void theScopesNarrowToMineClaimableAndMyGroups() {
        assertThat(search(TaskQuery.open().withScope(TaskQuery.Scope.MINE), ALICE)).containsExactly("t-alice");
        assertThat(search(TaskQuery.open().withScope(TaskQuery.Scope.MINE), ANONYMOUS)).isEmpty();
        assertThat(search(TaskQuery.open().withScope(TaskQuery.Scope.CANDIDATE), CAROL))
                .containsExactly("t-cand-group", "t-cand-user");
        assertThat(search(TaskQuery.open().withScope(TaskQuery.Scope.GROUPS), CAROL)).containsExactly("t-cand-group");
        assertThat(search(TaskQuery.open().withScope(TaskQuery.Scope.GROUPS), ALICE)).isEmpty();
    }

    @Test
    void overdueAndDueFiltersReadTheDueDateAgainstNow() {
        var overdue = new TaskQuery(null, List.of(FormExecutionStatus.ASSIGNED, FormExecutionStatus.COMPLETED),
                null, true, null, null, null, null, null, 0, 50);
        // A completed task is not overdue, whatever its due date said.
        assertThat(search(overdue, ADMIN)).containsExactly("t-alice");
        var dueSoon = new TaskQuery(null, null, null, null, NOW.plusDays(1).plusHours(1), null, null, null,
                TaskQuery.Sort.DUE, 0, 50);
        assertThat(search(dueSoon, ADMIN)).containsExactly("t-alice", "t-cand-group");
    }

    @Test
    void aPageCountsEverythingItIsAPageOf() {
        var page = db.search(new TaskQuery(null, null, null, null, null, null, null, null, null, 1, 3), ADMIN, null);

        assertThat(page.total()).isEqualTo(8);
        assertThat(page.page()).isEqualTo(1);
        assertThat(ids(page)).containsExactly("t-group-held", "t-legacy", "t-open");
    }

    @Test
    void theFormsTheCallerMayWorkOnNarrowTheInbox() {
        assertThat(ids(db.search(TaskQuery.open(), ADMIN, Set.of("refund")))).containsExactly("t-cand-group");
        assertThat(ids(memory.search(TaskQuery.open(), ADMIN, Set.of("refund")))).containsExactly("t-cand-group");
        assertThat(db.search(TaskQuery.open(), ADMIN, Set.of()).total()).isZero();
    }

    @Test
    void aClaimIsAtomicAndAssignsTheTask() {
        assertThat(db.claim("t-cand-group", "carol")).isTrue();
        assertThat(db.claim("t-cand-group", "erin")).isFalse();
        assertThat(db.claim("t-done", "erin")).isFalse();
        var claimed = db.findById("t-cand-group").orElseThrow();
        assertThat(claimed.userId()).isEqualTo("carol");
        assertThat(claimed.status()).isEqualTo(FormExecutionStatus.ASSIGNED);
        // What it offered survives the claim: a release hands it back to the same people.
        assertThat(claimed.candidateGroups()).containsExactly("finance");
        assertThat(memory.claim("t-cand-group", "carol")).isTrue();
        assertThat(memory.claim("t-cand-group", "erin")).isFalse();
        assertThat(memory.claim("t-done", "erin")).isFalse();
        assertThat(memory.claim("missing", "erin")).isFalse();
    }

    @Test
    void everyAssignmentColumnRoundTrips() {
        var task = task("t-full").userId("alice").status(FormExecutionStatus.ASSIGNED)
                .candidateUsers(List.of("bob", "carol")).candidateGroups(List.of("finance"))
                .priority(85).dueDate(NOW.plusDays(3)).followUpDate(NOW.plusDays(1)).businessKey("order-1")
                .createdAt(NOW).escalationLevel(2).escalatedAt(NOW.plusHours(1)).restricted(true)
                .values(List.of(new Value("decision", "OK"))).build();
        db.save(task);

        var stored = db.findById("t-full").orElseThrow();
        assertThat(stored.candidateUsers()).containsExactlyInAnyOrder("bob", "carol");
        assertThat(stored.candidateGroups()).containsExactly("finance");
        assertThat(stored.priority()).isEqualTo(85);
        assertThat(stored.dueDate()).isEqualTo(NOW.plusDays(3));
        assertThat(stored.followUpDate()).isEqualTo(NOW.plusDays(1));
        assertThat(stored.businessKey()).isEqualTo("order-1");
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(stored.escalationLevel()).isEqualTo(2);
        assertThat(stored.escalatedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(stored.isRestricted()).isTrue();
        assertThat(stored.values()).containsExactly(new Value("decision", "OK"));

        // Reassigned: the candidates are replaced, not added to.
        db.save(stored.withCandidateUsers(List.of()).withCandidateGroups(List.of("managers")));
        var reassigned = db.findById("t-full").orElseThrow();
        assertThat(reassigned.candidateUsers()).isEmpty();
        assertThat(reassigned.candidateGroups()).containsExactly("managers");
    }

    @Test
    void aRowFromBeforeAssignmentReadsAsTheDefaults() {
        var legacy = db.findById("t-legacy").orElseThrow();

        assertThat(legacy.priorityOrDefault()).isEqualTo(50);
        assertThat(legacy.isRestricted()).isFalse();
        assertThat(legacy.escalations()).isZero();
        assertThat(legacy.candidateUsers()).isEmpty();
        assertThat(legacy.createdAt()).isNull();
    }

    @Test
    void anEscalationFindsItsTaskByItsStepExecution() {
        assertThat(db.findByStepExecutionId("se-t-cand-group")).extracting(FormExecution::id)
                .containsExactly("t-cand-group");
        assertThat(memory.findByStepExecutionId("se-t-cand-group")).extracting(FormExecution::id)
                .containsExactly("t-cand-group");
        assertThat(db.findByStepExecutionId("se-nothing")).isEmpty();
    }
}
