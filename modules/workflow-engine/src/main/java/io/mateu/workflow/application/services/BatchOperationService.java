package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.BatchOperationRepository;
import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessNotFoundException;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.domain.batch.BatchOperation;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.domain.batch.BatchOperationStatus;
import io.mateu.workflow.domain.batch.BatchOperationType;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.search.SearchQuery;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Batch operations: create one over a filter, list them, cancel one — and {@link #runNextPage}, one page
 * of the oldest active batch, which the runner ({@code BatchOperationJob}) calls on one node at a time.
 *
 * <p><b>Each process is one ordinary command.</b> A page applies the batch's command to each process
 * through {@link ProcessOperationsService} — the door the REST API's single commands take — so each one
 * is judged by the definition's flow authorization against the caller who created the batch, refused by
 * the use case's own status rule when it does not apply, and carried out by the use case on the node
 * that owns the process, under its lock. What a batch adds is the walk: by process id, a page at a time,
 * throttled, its cursor and counters saved after every page so a restart carries on where it stopped,
 * and a cancellation that wins over the runner.
 *
 * <p>Outcomes per process: <em>succeeded</em> (the command was accepted), <em>skipped</em> (not in a status
 * the command applies to, gone since it was matched, or not authorized for the caller — by reason in
 * {@code skippedByReason}), <em>failed</em> (anything else; a sample is kept in {@code errors}).
 *
 * <p><b>Extending it.</b> A new type is one more entry in {@link #actions}: a function of the process id
 * and the batch (whose {@code parameters} carry what the type needs — a target version for MIGRATE, the
 * variables for SET_VARIABLES, a step for RETRY_FROM_STEP). An operation that today acts on a hand-made
 * group of processes ("retry all in group") belongs here as a type, so it gets the same walk, throttle,
 * resumption and record.
 */
@Service
@Slf4j
public class BatchOperationService {

    /** One process's command: throws to refuse (see the class comment for how refusals are counted). */
    @FunctionalInterface
    public interface BatchAction {
        void apply(String processId, BatchOperation batch);
    }

    private final BatchOperationRepository repository;
    private final ProcessRepository processRepository;
    private final ProcessListingService listing;
    private final BatchOperationProperties properties;
    private final ObjectProvider<WorkflowMetrics> metrics;
    /** How often, in processes, a page looks whether its batch was cancelled. */
    static final int CANCEL_CHECK_EVERY = 10;

    private final Map<BatchOperationType, BatchAction> actions = new EnumMap<>(BatchOperationType.class);
    private long lastItemAt;

    public BatchOperationService(BatchOperationRepository repository, ProcessRepository processRepository,
                                 ProcessListingService listing, ProcessOperationsService processes,
                                 BatchOperationProperties properties, ObjectProvider<WorkflowMetrics> metrics) {
        this.repository = repository;
        this.processRepository = processRepository;
        this.listing = listing;
        this.properties = properties;
        this.metrics = metrics;
        actions.put(BatchOperationType.CANCEL, (id, batch) -> processes.cancel(id, batch.caller()));
        actions.put(BatchOperationType.RETRY, (id, batch) -> processes.retry(id, batch.caller()));
        actions.put(BatchOperationType.RESTART, (id, batch) -> processes.restart(id, batch.caller()));
        actions.put(BatchOperationType.PAUSE, (id, batch) -> processes.pause(id, batch.caller()));
        actions.put(BatchOperationType.RESUME, (id, batch) -> processes.resume(id, batch.caller()));
    }

    // ── Create, read, cancel ──────────────────────────────────────────────────────────────────────

    /**
     * Creates a batch over the processes {@code filter} matches; the runner picks it up.
     *
     * @throws IllegalArgumentException no type, no filter, an empty filter, or a malformed attribute query
     */
    public BatchOperation create(BatchOperationType type, BatchOperationFilter filter, Map<String, String> parameters,
                                 AuthorizationContext caller) {
        if (type == null) {
            throw new IllegalArgumentException("A batch operation needs a type: one of "
                    + java.util.Arrays.toString(BatchOperationType.values()) + ".");
        }
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("A batch operation needs a filter — a definition, tag, status, business key,"
                    + " dates, text or search attributes. It never acts on every process by default.");
        }
        var listingFilter = listingFilter(filter);
        var matched = processRepository.searchSummaries(listingFilter, 0, 1).totalElements();
        var batch = BatchOperation.create(UUID.randomUUID().toString(), type, normalised(filter), parameters, matched,
                caller, LocalDateTime.now());
        repository.create(batch);
        log.info("Batch operation {} created: {} over {} process(es) ({}) by {}", batch.id(), type, matched,
                filter.describe(), batch.createdBy());
        return batch;
    }

    public Optional<BatchOperation> get(String id) {
        return repository.findById(id);
    }

    /** The most recent batches, newest first. */
    public List<BatchOperation> list() {
        return repository.findRecent(properties.getListLimit());
    }

    /**
     * Cancels a batch that has not finished: no further process is touched, what was done stays done.
     *
     * @return the batch as it is now
     * @throws BatchOperationNotFoundException       no such batch
     * @throws BatchOperationNotActiveException      it already finished
     */
    public BatchOperation cancel(String id) {
        var batch = repository.findById(id).orElseThrow(() -> new BatchOperationNotFoundException(id));
        if (!repository.cancel(id, LocalDateTime.now())) {
            throw new BatchOperationNotActiveException(id, batch.status());
        }
        metric().batchOperationFinished(batch.type().name(), "cancelled");
        log.info("Batch operation {} cancelled after {} process(es)", id, batch.processed());
        return repository.findById(id).orElse(batch);
    }

    /**
     * The listing filter a batch's filter stands for now: the tag resolved to the definitions carrying it,
     * the query parsed to its conditions.
     *
     * @throws IllegalArgumentException a malformed query
     */
    public ProcessListingFilter listingFilter(BatchOperationFilter filter) {
        return new ProcessListingFilter(filter.searchText(), filter.onlyErrors(), filter.workflowDefinitionId(),
                filter.status(), filter.createdFrom(), filter.createdTo(), filter.businessKey(),
                listing.definitionsTagged(filter.tag()), SearchQuery.parse(filter.query()));
    }

    /** The query written as its parser reads it back, so what is stored is what will run. */
    private static BatchOperationFilter normalised(BatchOperationFilter filter) {
        var query = filter.query() == null ? null : SearchQuery.format(SearchQuery.parse(filter.query()));
        return new BatchOperationFilter(filter.workflowDefinitionId(), filter.tag(), filter.status(),
                filter.createdFrom(), filter.createdTo(), filter.businessKey(), filter.searchText(),
                filter.onlyErrors(), query);
    }

    // ── The runner's page ─────────────────────────────────────────────────────────────────────────

    /**
     * Works one page of the oldest active batch: the next {@code page-size} matching processes after its
     * cursor, each acted on in id order at no more than {@code max-per-second}; then the cursor and the
     * counters are saved. A batch with nothing left after its cursor completes.
     *
     * @return whether there was a batch to work on
     */
    public boolean runNextPage() {
        var next = repository.findNextActive();
        if (next.isEmpty()) {
            return false;
        }
        var batch = next.get();
        var now = LocalDateTime.now();
        if (batch.status() == BatchOperationStatus.CREATED) {
            batch = batch.started(now);
            if (repository.saveProgress(batch) != BatchOperationStatus.RUNNING) {
                return true;
            }
        }
        ProcessListingFilter filter;
        try {
            filter = listingFilter(batch.filter());
        } catch (IllegalArgumentException unreadable) {
            finish(batch, BatchOperationStatus.FAILED, "The filter no longer reads: " + unreadable.getMessage());
            return true;
        }
        var ids = processRepository.findIdsMatching(filter, batch.cursor(), Math.max(1, properties.getPageSize()));
        if (ids.isEmpty()) {
            finish(batch, BatchOperationStatus.COMPLETED, null);
            return true;
        }
        long succeeded = 0;
        long failed = 0;
        var skipped = new LinkedHashMap<String, Long>();
        var errors = new ArrayList<String>();
        String last = null;
        var action = actions.get(batch.type());
        var visited = 0;
        for (var processId : ids) {
            // A cancellation lands between two processes, not at the end of the page.
            if (visited > 0 && visited % CANCEL_CHECK_EVERY == 0 && cancelled(batch.id())) {
                break;
            }
            visited++;
            throttle();
            try {
                action.apply(processId, batch);
                succeeded++;
            } catch (ProcessStateConflictException notApplicable) {
                skipped.merge(BatchOperation.SKIPPED_NOT_APPLICABLE, 1L, Long::sum);
            } catch (FlowAuthorizationDeniedException notAuthorized) {
                skipped.merge(BatchOperation.SKIPPED_NOT_AUTHORIZED, 1L, Long::sum);
            } catch (ProcessNotFoundException gone) {
                skipped.merge(BatchOperation.SKIPPED_GONE, 1L, Long::sum);
            } catch (RuntimeException error) {
                failed++;
                errors.add(processId + ": " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                log.warn("Batch operation {} could not {} process {}", batch.id(), batch.type(), processId, error);
            }
            last = processId;
        }
        var progressed = batch.progressed(last, succeeded, failed, skipped, errors, LocalDateTime.now());
        var stored = repository.saveProgress(progressed);
        var type = batch.type().name();
        metric().batchOperationItems(type, "succeeded", succeeded);
        metric().batchOperationItems(type, "failed", failed);
        metric().batchOperationItems(type, "skipped", skipped.values().stream().mapToLong(Long::longValue).sum());
        if (stored == BatchOperationStatus.RUNNING && visited == ids.size()
                && ids.size() < Math.max(1, properties.getPageSize())) {
            // A short page is the last one: nothing after it matched when it was read.
            finish(progressed, BatchOperationStatus.COMPLETED, null);
        }
        return true;
    }

    private boolean cancelled(String id) {
        return repository.findById(id).map(stored -> stored.status() == BatchOperationStatus.CANCELLED).orElse(true);
    }

    private void finish(BatchOperation batch, BatchOperationStatus outcome, String why) {
        var stored = repository.saveProgress(batch.finished(outcome, why, LocalDateTime.now()));
        if (stored == outcome) {
            metric().batchOperationFinished(batch.type().name(), outcome.name().toLowerCase(java.util.Locale.ROOT));
            log.info("Batch operation {} {}: {} succeeded, {} failed, {} skipped{}", batch.id(),
                    outcome.name().toLowerCase(java.util.Locale.ROOT), batch.succeeded(), batch.failed(),
                    batch.skipped(), why == null ? "" : " — " + why);
        }
    }

    /** Spaces the commands out to {@code max-per-second}. One runner thread per node: no locking needed. */
    private void throttle() {
        var perSecond = properties.getMaxPerSecond();
        if (perSecond <= 0) {
            return;
        }
        var gapNanos = 1_000_000_000L / perSecond;
        var wait = lastItemAt + gapNanos - System.nanoTime();
        if (wait > 0) {
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastItemAt = System.nanoTime();
    }

    private WorkflowMetrics metric() {
        var found = metrics.getIfAvailable();
        return found == null ? new WorkflowMetrics() {} : found;
    }

    /** No batch with this id. */
    public static class BatchOperationNotFoundException extends RuntimeException {
        public BatchOperationNotFoundException(String id) {
            super("No batch operation with id '" + id + "'.");
        }
    }

    /** The batch already finished, so there is nothing to cancel. */
    public static class BatchOperationNotActiveException extends RuntimeException {
        public BatchOperationNotActiveException(String id, BatchOperationStatus status) {
            super("Batch operation '" + id + "' is " + status + " and cannot be cancelled.");
        }
    }
}
