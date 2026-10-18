package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.ProcessListingService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.search.SearchAttributeFilter;
import io.mateu.workflow.search.SearchAttributeFilter.Op;
import io.mateu.workflow.search.SearchAttributeType;
import io.mateu.workflow.search.SearchAttributeValue;
import io.mateu.workflow.search.SearchQuery;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Search attributes and batch operations on a migrated database (V48, V49) — H2 on every build and
 * PostgreSQL wherever Docker is: the attribute rows, the listing narrowed by them (keyword, text, number
 * and date conditions, with the other filters), the key-ordered walk a batch takes over a large filter,
 * and the batch table — a cancellation that wins over the runner, and a batch resumed by a new service
 * (a restarted pod) that acts on every process exactly once.
 */
abstract class SearchAndBatchContract {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 9, 0);

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    io.mateu.workflow.application.out.ProcessSearchAttributeRepository attributes;
    ProcessDBRepository processes;
    io.mateu.workflow.application.out.BatchOperationRepository batches;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void open() {
        dataSource = newMigratedDatabase();
        context = AnalyticsJpaTestContext.open(dataSource);
        jdbc = context.getBean(JdbcTemplate.class);
        // Created by the context, so their @Transactional methods are proxied as in the engine.
        var beans = context.getAutowireCapableBeanFactory();
        attributes = (io.mateu.workflow.application.out.ProcessSearchAttributeRepository) beans.createBean(JpaProcessSearchAttributeRepository.class);
        batches = (io.mateu.workflow.application.out.BatchOperationRepository) beans.createBean(JpaBatchOperationRepository.class);
        processes = new ProcessDBRepository(context.getBean(ProcessEntityRepository.class), null, null, null, null,
                null, null, null, null, null, null);
        processes.entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
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

    void process(String id, String definition, String status, LocalDateTime created) {
        jdbc.update("INSERT INTO process_entity (id, business_key, name, status, workflow_definition_id, created) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, "bk-" + id, "Order", status, definition, Timestamp.valueOf(created));
    }

    static SearchAttributeValue v(String name, SearchAttributeType type, String raw) {
        return SearchAttributeValue.of(name, type, raw).orElseThrow();
    }

    List<String> ids(ProcessListingFilter filter) {
        return processes.searchSummaries(filter, 0, 100).content().stream().map(s -> s.id()).sorted().toList();
    }

    static ProcessListingFilter where(String query) {
        return ProcessListingFilter.of(null, false).withSearchAttributes(SearchQuery.parse(query));
    }

    @Test
    void theListingIsNarrowedByEveryKindOfCondition() {
        process("p1", "orders", "RUNNING", T0);
        process("p2", "orders", "ERROR", T0.plusMinutes(1));
        process("p3", "refunds", "RUNNING", T0.plusMinutes(2));
        attributes.replace("p1", List.of(v("customerId", SearchAttributeType.KEYWORD, "C-9"),
                v("amount", SearchAttributeType.NUMBER, "150"), v("placedOn", SearchAttributeType.DATE, "2026-10-01T10:00:00"),
                v("note", SearchAttributeType.TEXT, "Rush 100% order"), v("vip", SearchAttributeType.BOOL, "true")));
        attributes.replace("p2", List.of(v("customerId", SearchAttributeType.KEYWORD, "C-9"),
                v("amount", SearchAttributeType.NUMBER, "50"), v("placedOn", SearchAttributeType.DATE, "2026-10-05")));
        attributes.replace("p3", List.of(v("customerId", SearchAttributeType.KEYWORD, "C-7"),
                v("amount", SearchAttributeType.NUMBER, "500")));

        assertThat(ids(where("customerId = C-9"))).containsExactly("p1", "p2");
        assertThat(ids(where("customerId = c-9"))).isEmpty();
        assertThat(ids(where("amount > 100"))).containsExactly("p1", "p3");
        assertThat(ids(where("amount >= 50 AND amount <= 150"))).containsExactly("p1", "p2");
        assertThat(ids(where("amount = 150.0"))).containsExactly("p1");
        assertThat(ids(where("placedOn <= 2026-10-01"))).containsExactly("p1");
        assertThat(ids(where("placedOn > 2026-10-01"))).containsExactly("p2");
        assertThat(ids(where("note = '100%'"))).containsExactly("p1");
        assertThat(ids(where("note = 'rush'"))).containsExactly("p1");
        assertThat(ids(where("vip = true"))).containsExactly("p1");
        assertThat(ids(where("customerId = \"x' OR '1'='1\""))).isEmpty();

        // With the other filters, all ANDed — and counted and paged like the fixed query.
        var running = new ProcessListingFilter(null, false, null, Set.of(ProcessStatus.RUNNING), null, null, null,
                Set.of("orders", "refunds"), SearchQuery.parse("amount > 100"));
        assertThat(ids(running)).containsExactly("p1", "p3");
        var page = processes.searchSummaries(new ProcessListingFilter("bk-p", true, "orders", null, T0, T0.plusDays(1),
                "p2", null, SearchQuery.parse("customerId = C-9")), 0, 10);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().getFirst().id()).isEqualTo("p2");
        assertThat(page.content().getFirst().workflowDefinitionId()).isEqualTo("orders");
        var newestFirst = processes.searchSummaries(where("amount > 0"), 0, 2);
        assertThat(newestFirst.totalElements()).isEqualTo(3);
        assertThat(newestFirst.content()).extracting(s -> s.id()).containsExactly("p3", "p2");

        // Replaced, read back, forgotten.
        attributes.replace("p1", List.of(v("customerId", SearchAttributeType.KEYWORD, "C-1")));
        assertThat(attributes.findByProcessId("p1")).extracting(SearchAttributeValue::keyword).containsExactly("C-1");
        assertThat(attributes.findByProcessIds(List.of("p1", "p2", "none")).keySet()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(attributes.deleteByProcessIds(List.of("p2", "p3"))).isEqualTo(5);
        assertThat(ids(where("amount > 0"))).isEmpty();
        attributes.replace("p1", List.of());
        assertThat(attributes.findByProcessId("p1")).isEmpty();
    }

    @Test
    void aLargeFilterIsWalkedByKeyWithNothingMissedOrRepeated() {
        var all = new ArrayList<String>();
        for (int i = 0; i < 250; i++) {
            var id = String.format("p%04d", i);
            all.add(id);
            process(id, i % 2 == 0 ? "orders" : "refunds", "RUNNING", T0.plusSeconds(i));
            attributes.replace(id, List.of(v("tenant", SearchAttributeType.KEYWORD, i % 5 == 0 ? "t1" : "t2")));
        }
        var filter = new ProcessListingFilter(null, false, "orders", null, null, null, null, null,
                SearchQuery.parse("tenant = t2"));
        var walked = new ArrayList<String>();
        String cursor = null;
        while (true) {
            var page = processes.findIdsMatching(filter, cursor, 40);
            if (page.isEmpty()) {
                break;
            }
            walked.addAll(page);
            cursor = page.getLast();
        }
        var expected = all.stream().filter(id -> {
            int i = Integer.parseInt(id.substring(1));
            return i % 2 == 0 && i % 5 != 0;
        }).toList();
        assertThat(walked).containsExactlyElementsOf(expected);
        assertThat(processes.findIdsMatching(ProcessListingFilter.of(null, false), null, 0)).isEmpty();
        assertThat(processes.findIdsMatching(new ProcessListingFilter(null, false, null, Set.of(ProcessStatus.ERROR),
                null, null), null, 10)).isEmpty();
    }

    @Test
    void theBatchTableRoundTripsAndACancellationWinsOverTheRunner() {
        var caller = new AuthorizationContext("ana", List.of("orders:write"), List.of("ops"));
        var filter = new BatchOperationFilter("orders", "billing", Set.of(ProcessStatus.ERROR), T0, T0.plusDays(1), "bk",
                "text", true, "amount > 100");
        var batch = BatchOperation.create("b1", BatchOperationType.RETRY, filter, java.util.Map.of("k", "v"), 12, caller, T0);
        batches.create(batch);
        batches.create(BatchOperation.create("b2", BatchOperationType.CANCEL, filter, null, 3, null, T0.plusMinutes(1)));

        var read = batches.findById("b1").orElseThrow();
        assertThat(read.filter()).isEqualTo(filter);
        assertThat(read.caller()).isEqualTo(caller);
        assertThat(read.parameters()).containsEntry("k", "v");
        assertThat(batches.findRecent(10)).extracting(BatchOperation::id).containsExactly("b2", "b1");
        assertThat(batches.findNextActive().orElseThrow().id()).isEqualTo("b1");

        // b2 running goes first, whatever its age.
        assertThat(batches.saveProgress(batches.findById("b2").orElseThrow().started(T0))).isEqualTo(BatchOperationStatus.RUNNING);
        assertThat(batches.findNextActive().orElseThrow().id()).isEqualTo("b2");

        var running = read.started(T0);
        batches.saveProgress(running);
        assertThat(batches.cancel("b1", T0.plusMinutes(5))).isTrue();
        assertThat(batches.cancel("b1", T0.plusMinutes(6))).isFalse();
        assertThat(batches.cancel("nope", T0)).isFalse();
        // The runner's late progress is recorded, the cancellation kept.
        var stored = batches.saveProgress(running.progressed("p9", 4, 1, java.util.Map.of("notApplicable", 2L),
                List.of("p3: boom"), T0.plusMinutes(7)));
        assertThat(stored).isEqualTo(BatchOperationStatus.CANCELLED);
        var after = batches.findById("b1").orElseThrow();
        assertThat(after.status()).isEqualTo(BatchOperationStatus.CANCELLED);
        assertThat(after.succeeded()).isEqualTo(4);
        assertThat(after.skippedByReason()).containsEntry("notApplicable", 2L);
        assertThat(after.errors()).containsExactly("p3: boom");
        assertThat(after.cursor()).isEqualTo("p9");
        assertThat(after.finishedAt()).isEqualTo(T0.plusMinutes(5));
    }

    @Test
    void aBatchResumedByARestartedNodeActsOnEveryProcessOnceAndSkipsTheUnauthorized() {
        for (int i = 0; i < 95; i++) {
            process(String.format("p%03d", i), i == 7 ? "secret" : "orders", "ERROR", T0.plusSeconds(i));
        }
        var acted = Collections.synchronizedList(new ArrayList<String>());
        var operations = mock(ProcessOperationsService.class);
        doAnswer(call -> {
            String id = call.getArgument(0);
            if (id.equals("p007")) {
                throw FlowAuthorizationDeniedException.of("retry process 'p007'", "ana", List.of("secret"), List.of());
            }
            if (id.equals("p011")) {
                throw new IllegalStateException("broker down");
            }
            acted.add(id);
            return null;
        }).when(operations).retry(anyString(), any());
        var properties = new BatchOperationProperties();
        properties.setPageSize(40);
        properties.setMaxPerSecond(0);
        var listing = new ProcessListingService(processes, new StaticListableBeanFactory().getBeanProvider(
                io.mateu.workflow.application.out.ProcessIndexRepository.class), new ProcessStatusAnnouncer(false, ""),
                new StaticListableBeanFactory().getBeanProvider(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class));
        var metrics = new StaticListableBeanFactory().getBeanProvider(io.mateu.workflow.application.out.WorkflowMetrics.class);

        var first = new BatchOperationService(batches, processes, listing, operations, properties, metrics);
        var created = first.create(BatchOperationType.RETRY,
                new BatchOperationFilter(null, null, Set.of(ProcessStatus.ERROR), null, null, null, null, false, null),
                null, new AuthorizationContext("ana", List.of(), List.of()));
        assertThat(created.matched()).isEqualTo(95);
        assertThat(first.runNextPage()).isTrue();
        assertThat(batches.findById(created.id()).orElseThrow().cursor()).isEqualTo("p039");

        // The pod dies; another one — a new service over the same table — carries on from the cursor.
        var second = new BatchOperationService(batches, processes, listing, operations, properties, metrics);
        while (second.runNextPage()) {
            // until nothing is active
        }
        var done = batches.findById(created.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(BatchOperationStatus.COMPLETED);
        assertThat(done.succeeded()).isEqualTo(93);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.skippedByReason()).containsEntry(BatchOperation.SKIPPED_NOT_AUTHORIZED, 1L);
        assertThat(done.errors()).containsExactly("p011: broker down");
        assertThat(done.matched()).isEqualTo(95);
        assertThat(done.percent()).isEqualTo(100);
        assertThat(acted).doesNotHaveDuplicates().hasSize(93);
    }
}
