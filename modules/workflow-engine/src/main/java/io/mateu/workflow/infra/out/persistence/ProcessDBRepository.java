package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.AnalyticsAggregates;
import io.mateu.workflow.application.out.ProcessAnalyticsRow;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.paging.ServedPage;
import io.mateu.workflow.application.out.ProcessSummary;
import io.mateu.workflow.application.out.ProcessSummaryPage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class ProcessDBRepository implements ProcessRepository {

    final ProcessEntityRepository processEntityRepository;
    final OutboxMessageEntityRepository outboxMessageEntityRepository;
    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final io.mateu.workflow.infra.out.async.OutboxSignal outboxSignal;
    final io.mateu.workflow.application.out.ProcessTraceContextRepository processTraceContexts;
    final io.mateu.workflow.application.services.ProcessStatusAnnouncer processStatusAnnouncer;
    // Present only when the read model is switched on (workflow.analytics.rollup=true); empty
    // otherwise, and then aggregateProcesses falls through to the GROUP BY below unchanged.
    final org.springframework.beans.factory.ObjectProvider<
            io.mateu.workflow.application.out.RollupAnalyticsPort> rollupAnalytics;
    // Providers, not direct dependencies: both read processes back through this repository.
    final org.springframework.beans.factory.ObjectProvider<
            io.mateu.workflow.application.sync.EngineReplyPolicy> engineReplyPolicy;
    final org.springframework.beans.factory.ObjectProvider<
            io.mateu.workflow.application.out.ReplySignal> replySignal;
    final org.springframework.beans.factory.ObjectProvider<ReplyNotifier> replyNotifier;
    final io.mateu.workflow.application.services.SearchAttributeIndexer searchAttributeIndexer;

    /**
     * For the listing queries built per filter (search attributes, the batch walk). The shared,
     * transaction-bound EntityManager, constructor-injected and final like every other collaborator:
     * the admin UI rebuilds the views holding this repository from their state and treats final
     * fields as injected — a field-injected one came back null — and never serializes it (it is a
     * JDK proxy).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    final jakarta.persistence.EntityManager entityManager;

    /**
     * Seals the sensitive variables (and the reply, and the outbox rows) on the way to the database and
     * opens them on the way back — see VARIABLE-ENCRYPTION-PLAN.md. Final and constructor-injected like
     * the rest, for the reason given on {@link #entityManager}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    final io.mateu.workflow.application.services.VariableProtection protection;

    private io.mateu.workflow.application.services.VariableProtection protection() {
        return protection == null ? io.mateu.workflow.application.services.VariableProtection.none() : protection;
    }

    @Override
    public Optional<Process> findById(String id) {
        return processEntityRepository.findById(id)
                .map(this::map);
    }

    @Override
    public ChildrenPage findChildren(String parentProcessId, java.util.Set<String> parentStepIds, String stepId, int page, int size) {
        var found = processEntityRepository.findDirectChildren(parentProcessId, stepId, PageRequest.of(page, size));
        return new ChildrenPage(found.getContent().stream().map(this::map).toList(), page, size, found.getTotalElements());
    }

    private Process map(ProcessEntity entity) {
        var process = new Process(
                entity.getId(),
                entity.getName(),
                entity.getWorkflowDefinitionId(),
                entity.getWorkflowDefinitionVersion(),
                entity.getWorkflowDefinitionJson(),
                entity.getBusinessKey() != null ? entity.getBusinessKey() : entity.getReleasedBusinessKey(),
                protection().variablesFromJson(entity.getVariables()),
                ProcessStatus.valueOf(entity.getStatus()),
                entity.getCompletionPercentage(),
                entity.getCreated(),
                entity.getStarted(),
                entity.getFinished(),
                entity.getPausedAt(),
                entity.getParentStepExecutionId(),
                entity.getVersion(),
                replyOf(entity, protection()),
                entity.getContinuedFromProcessId(),
                entity.getContinuedAsProcessId(),
                entity.getBusinessKey() == null && entity.getReleasedBusinessKey() != null,
                entity.getInitiatingActor(), entity.isTestRun(), entity.getTestRunRequestDigest()
        );
        // Registered, so its step executions and events find the process's policy without loading it again.
        protection().policies().of(process);
        return process;
    }

    /** The recorded reply, or null while the process has not replied (no outcome stored). */
    static io.mateu.workflow.domain.aggregates.ProcessReply replyOf(ProcessEntity entity) {
        return replyOf(entity, io.mateu.workflow.application.services.VariableProtection.none());
    }

    /** The recorded reply, opened (it is sealed whole when the process has sensitive variables). */
    static io.mateu.workflow.domain.aggregates.ProcessReply replyOf(ProcessEntity entity,
            io.mateu.workflow.application.services.VariableProtection protection) {
        if (entity.getReplyOutcome() == null) {
            return null;
        }
        return new io.mateu.workflow.domain.aggregates.ProcessReply(
                entity.getReplyStepId(),
                protection.openBlob(entity.getReplyJson()),
                io.mateu.workflow.domain.aggregates.ProcessReply.Outcome.valueOf(entity.getReplyOutcome()),
                entity.getReplyCompensation() == null ? null
                        : io.mateu.workflow.domain.aggregates.ProcessReply.Compensation.valueOf(entity.getReplyCompensation()),
                entity.getReplyError(),
                entity.getRepliedAt());
    }

    @Override
    public String save(Process process) {
        // The failure contract of synchronous invocation: a process that reaches an end without
        // having replied gets the engine's answer recorded now, in this save — so it commits with
        // the transition that decided it, whichever use case made that transition.
        engineReplyPolicy.ifAvailable(policy -> policy.apply(process));
        // Read-model event, emitted at the one point every status transition funnels through: if the
        // read model is on and this save changes the status, ride a ProcessStatusChanged through the
        // outbox alongside the other domain events. Off → no prior-status read, no event.
        // Search attributes, at the same chokepoint: recomputed from the variables, stored when they changed.
        var searchAttributes = searchAttributeIndexer.index(process);
        if (processStatusAnnouncer.isEnabled()) {
            var previousStatus = processEntityRepository.findById(process.getId())
                    .map(entity -> ProcessStatus.valueOf(entity.getStatus()))
                    .orElse(null);
            processStatusAnnouncer.announceIfChanged(process, previousStatus, searchAttributes);
        }
        // Normalize empty businessKey to null: no key, nothing to claim.
        var businessKey = (process.getBusinessKey() == null || process.getBusinessKey().isBlank())
                ? null : process.getBusinessKey();
        // A run that handed its key over keeps it apart: the UNIQUE column names only the run holding it.
        var released = businessKey != null && process.isBusinessKeyReleased();
        var reply = process.getReply();
        // Whether this save is the one that records the reply — the moment the other pods are told.
        // Read before the save: afterwards the row says replied either way.
        boolean newlyReplied = reply != null && processEntityRepository.findById(process.getId())
                .map(entity -> entity.getRepliedAt() == null).orElse(true);
        // What this process's definition version declares sensitive: sealed in its row, its reply and the
        // events it emits; everything else as it was.
        var sensitive = protection().policies().of(process);
        processEntityRepository.save(new ProcessEntity(
                process.getId(),
                released ? null : businessKey,
                process.getName(),
                protection().variablesJson(process.getVariables(), sensitive),
                process.getStatus().name(),
                process.getCompletionPercentage(),
                "log",
                process.getWorkflowDefinitionId(),
                process.getWorkflowDefinitionVersion(),
                process.getWorkflowDefinitionJson(),
                process.getCreated(),
                process.getStarted(),
                process.getFinished(),
                process.getPausedAt(),
                process.getParentStepExecutionId(),
                process.getVersion(),
                reply == null ? null : reply.stepId(),
                reply == null ? null : protection().sealBlob(reply.payload(), sensitive),
                reply == null ? null : reply.outcome().name(),
                reply == null ? null : reply.compensation().name(),
                reply == null ? null : reply.error(),
                reply == null ? null : reply.repliedAt(),
                process.getContinuedFromProcessId(),
                process.getContinuedAsProcessId(),
                released ? businessKey : null,
                process.getInitiatingActor(), process.isTestRun(), process.getTestRunRequestDigest()
        ));
        // Captured here, at the one moment the event and the context that produced it are both
        // in hand: the relay publishes this row later, from a thread that has neither.
        var traceContext = workflowTracing.currentTraceContext();
        var outbox = process.popEvents().stream()
                .map(event -> {
                    var row = new OutboxMessageEntity(event, traceContext);
                    row.setPayload(protection().sealPayload(row.getPayload(), sensitive));
                    // Driving this process inline (the synchronous fast path): its own rows are
                    // written already claimed by this pod, so no relay races the driver for them.
                    var claim = io.mateu.workflow.infra.out.async.InlineDrive.claimFor(event);
                    return claim == null ? row : row.claimedBy(claim.processId(), claim.pod(), claim.claimUntil());
                }).toList();
        outboxMessageEntityRepository.saveAll(outbox);
        if (outbox.stream().anyMatch(row -> OutboxMessageStatus.Pending.name().equals(row.getStatus()))) {
            // Wake this pod's relay once the transaction commits, rather than leaving the row to
            // be found on the next poll — which is latency added to every step.
            outboxSignal.raise();
        }
        if (newlyReplied) {
            // A caller waiting on another pod: NOTIFY rides this transaction, delivered on commit.
            replyNotifier.ifAvailable(notifier -> notifier.replyRecorded(process.getId()));
        }
        if (reply != null) {
            // A caller waiting on this pod hears on commit rather than on the next poll. Cheap when
            // nobody is waiting (a map lookup), so it is said on every save of a replied process
            // rather than working out whether this is the save that recorded it.
            replySignal.ifAvailable(signal -> signal.replyRecorded(process.getId()));
        }
        return process.getId();
    }

    @Override
    public java.util.Set<String> findRepliedAmong(java.util.Collection<String> processIds) {
        if (processIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(processEntityRepository.findRepliedIdsIn(processIds));
    }

    @Override
    public List<Process> findAll() {
        return processEntityRepository.findAll().stream().map(this::map).toList();
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        processEntityRepository.deleteAllById(selectedIds);
        processTraceContexts.deleteAllByProcessId(selectedIds);
    }

    @Override
    public Optional<Process> findByBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return Optional.empty();
        }
        // The run that holds the key now: the UNIQUE column names it and nothing else.
        return processEntityRepository.findByBusinessKey(businessKey).map(this::map);
    }

    @Override
    public List<Process> findAllByBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return List.of();
        }
        return processEntityRepository.findAllRunsOfBusinessKey(businessKey).stream().map(this::map).toList();
    }

    /**
     * The release goes through the ordinary save (so the optimistic version fences a concurrent takeover: two
     * starts racing to take the key from one run cannot both win) and is flushed at once: the next run's insert
     * of the same key must reach the UNIQUE column after it, and Hibernate orders a flush's inserts first.
     */
    @Override
    public void releaseBusinessKey(String processId) {
        findById(processId).ifPresent(process -> {
            process.releaseBusinessKey();
            save(process);
            flush();
        });
    }

    /**
     * Inside a transaction, writes what it holds now; outside one there is nothing to write — each save has
     * already committed on its own (the release then commits before the next run's insert, and the UNIQUE
     * column still refuses a second holder if another start slips in between).
     */
    @Override
    public void flush() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.flush();
        }
    }

    @Override
    public long countByStatus(ProcessStatus status) {
        return processEntityRepository.countByStatus(status.name());
    }

    /**
     * Six {@code GROUP BY}s instead of every process in the window.
     *
     * <p>The report is about fifty rows on screen. Folding it in Java meant materialising every
     * process that fed it — 37 651 on the deployment this was measured against, and the step half
     * was ten times that. The database does the same reduction and returns one row per definition
     * and status.
     */
    @Override
    public AnalyticsAggregates.ProcessAggregates aggregateProcesses(LocalDateTime from, LocalDateTime to) {
        var rollup = rollupAnalytics.getIfAvailable();
        if (rollup != null) {
            return rollup.aggregateProcesses(from, to);
        }
        var statusCounts = processEntityRepository.aggregateStatusCounts(from, to).stream()
                .map(v -> new AnalyticsAggregates.DefinitionStatusCount(
                        v.getDefinitionId(), ProcessStatus.valueOf(v.getStatus()), v.getCount(), v.getAnyName()))
                .toList();
        return new AnalyticsAggregates.ProcessAggregates(
                statusCounts,
                perDay(processEntityRepository.aggregateCreatedPerDay(from, to)),
                perDay(processEntityRepository.aggregateFinishedPerDay(from, to)),
                processEntityRepository.aggregateDurations(from, to).stream()
                        .map(v -> new AnalyticsAggregates.DefinitionDuration(v.getDefinitionId(),
                                new AnalyticsAggregates.DurationAggregate(
                                        v.getSamples(), v.getTotalNanos(), v.getP95Nanos())))
                        .toList());
    }

    private static List<AnalyticsAggregates.DefinitionDayCount> perDay(
            List<ProcessEntityRepository.DayCountView> views) {
        return views.stream()
                .map(v -> new AnalyticsAggregates.DefinitionDayCount(
                        v.getDefinitionId(), v.getDay(), v.getCount()))
                .toList();
    }

    /**
     * The window goes into the query, and the query selects seven columns instead of the row. The
     * default would have loaded every process in the window as a full aggregate — definition JSON
     * included — to compute counts and averages over it.
     */
    @Override
    public List<ProcessAnalyticsRow> findAnalyticsRows(LocalDateTime createdFrom, LocalDateTime createdTo) {
        return processEntityRepository.findAnalyticsRows(createdFrom, createdTo).stream()
                .map(view -> new ProcessAnalyticsRow(
                        view.getId(),
                        view.getName(),
                        view.getWorkflowDefinitionId(),
                        ProcessStatus.valueOf(view.getStatus()),
                        view.getCreated(),
                        view.getStarted(),
                        view.getFinished()))
                .toList();
    }

    /**
     * Pushed all the way down to SQL, unlike the in-memory default: the write-side process row
     * carries the workflow definition JSON, so loading the table to show ten rows moves hundreds of
     * megabytes per keystroke once a deployment has been running for a while.
     */
    /** The list bound to an {@code in} that its "no filter" flag switches off: never null, never empty. */
    private static final List<String> NO_FILTER = List.of("");

    @Override
    public ProcessSummaryPage searchSummaries(
            io.mateu.workflow.application.out.ProcessListingFilter filter, int page, int size) {
        if (filter.normalisedSearchAttributes() != null) {
            return searchWithAttributes(filter, page, size);
        }
        var searchText = filter.normalisedSearchText();
        var pattern = searchText == null ? null : "%" + searchText.toLowerCase() + "%";
        var businessKey = filter.normalisedBusinessKey();
        var businessKeyPattern = businessKey == null ? null : "%" + businessKey.toLowerCase() + "%";
        // The statuses travel as their names: the column is a string, and passing the enum would
        // leave the comparison to however the provider chooses to bind it. No status filter is a
        // flag plus a placeholder list (see searchSummaries): never a null or empty list in an IN.
        var anyStatus = filter.normalisedStatuses() == null;
        var statuses = anyStatus ? NO_FILTER : filter.normalisedStatuses().stream().map(Enum::name).toList();
        // Counted first, because which page can be served depends on how many there are — see
        // ServedPage. Two queries either way: a Spring Data Page would have run this same count.
        var anyDefinition = filter.normalisedDefinitionIds() == null;
        java.util.Collection<String> definitionIds = anyDefinition ? NO_FILTER : filter.normalisedDefinitionIds();
        var total = processEntityRepository.countSummaries(filter.onlyErrors(), pattern, businessKeyPattern,
                filter.workflowDefinitionId(), anyDefinition, definitionIds, anyStatus, statuses,
                filter.createdFrom(), filter.createdTo());
        var served = ServedPage.of(page, size, total);
        var content = processEntityRepository
                .searchSummaries(filter.onlyErrors(), pattern, businessKeyPattern,
                        filter.workflowDefinitionId(), anyDefinition, definitionIds, anyStatus, statuses,
                        filter.createdFrom(), filter.createdTo(), PageRequest.of(served.number(), served.size()))
                .stream()
                .map(view -> new ProcessSummary(
                        view.getId(),
                        view.getName(),
                        ProcessStatus.valueOf(view.getStatus()),
                        view.getCompletionPercentage(),
                        view.getCreated(),
                        view.getStarted(),
                        view.getFinished(),
                        view.getBusinessKey(),
                        view.getWorkflowDefinitionId()))
                .toList();
        return new ProcessSummaryPage(content, total, served.number(), served.size());
    }

    /** The listing narrowed by search attributes: the same conditions, built per filter (see ProcessListingJpql). */
    private ProcessSummaryPage searchWithAttributes(io.mateu.workflow.application.out.ProcessListingFilter filter,
                                                    int page, int size) {
        var where = ProcessListingJpql.of(filter);
        var count = entityManager.createQuery("select count(p) from ProcessEntity p" + where.text(), Long.class);
        where.params().forEach(count::setParameter);
        long total = count.getSingleResult();
        var served = ServedPage.of(page, size, total);
        var select = entityManager.createQuery("select p.id, p.name, p.status, p.completionPercentage, p.created,"
                + " p.started, p.finished, p.businessKey, p.workflowDefinitionId from ProcessEntity p"
                + where.text() + " order by p.created desc nulls last", Object[].class);
        where.params().forEach(select::setParameter);
        select.setFirstResult((int) served.offset());
        select.setMaxResults(served.size());
        var content = select.getResultList().stream()
                .map(row -> new ProcessSummary((String) row[0], (String) row[1],
                        ProcessStatus.valueOf((String) row[2]), ((Number) row[3]).intValue(),
                        (LocalDateTime) row[4], (LocalDateTime) row[5], (LocalDateTime) row[6],
                        (String) row[7], (String) row[8]))
                .toList();
        return new ProcessSummaryPage(content, total, served.number(), served.size());
    }

    /** One query: the filter's conditions, after the id, in id order — the batch operation's walk. */
    @Override
    public List<String> findIdsMatching(io.mateu.workflow.application.out.ProcessListingFilter filter,
                                        String afterId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var where = ProcessListingJpql.of(filter);
        var query = entityManager.createQuery("select p.id from ProcessEntity p" + where.text()
                + (afterId == null ? "" : " and p.id > :afterId") + " order by p.id", String.class);
        where.params().forEach(query::setParameter);
        if (afterId != null) {
            query.setParameter("afterId", afterId);
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    @Override
    public List<String> findStalled(LocalDateTime idleBefore, int limit) {
        return processEntityRepository.findStalled(idleBefore,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

}
