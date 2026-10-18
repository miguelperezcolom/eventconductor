package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPurgeRepository;
import io.mateu.workflow.application.out.ProcessPurgeRepository.PurgeCandidate;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.ProcessRetention;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deletes finished processes once their retention has passed — the decisions of the retention purge;
 * {@code ProcessRetentionJob} only runs it on a schedule, on one pod at a time.
 *
 * <h2>What goes, and when</h2>
 *
 * <p>A <b>process tree</b>: a root — a process with no parent, or whose parent is already gone — and
 * every process its PROCESS steps started, recursively. A child is never purged on its own, so a
 * parent never loses the record of a child it may still be looking at; the tree goes when its root's
 * retention has passed (the root's definition and status decide; the children's own retentions do
 * not hold it back), all of it in one transaction.
 *
 * <p>Only when every process of the tree is terminal — COMPLETED, CANCELLED, ERROR, COMPENSATED,
 * COMPENSATION_FAILED — with an end time, no step at work or waiting to retry, no lock held or
 * awaited and no outbox message unsent. Anything else keeps the whole tree, and it is looked at again
 * on the next pass. The store re-checks all of it inside the deleting transaction, so an operator who
 * retries an ERROR process while the purge is looking at it wins: nothing is deleted.
 *
 * <p>ERROR is purgeable like any terminal status, by its own retention — once purged it can no longer
 * be retried. A purged process's business key is free again: a new process may take it.
 *
 * <h2>Which retention</h2>
 *
 * <p>{@link ProcessRetentionPolicy}: the definition's {@code retention} as it is <em>now</em> — it is
 * metadata, not versioned, so a process is judged by its definition's current retention whatever
 * version it ran — then the engine defaults. Processes of a definition that no longer exists are
 * judged by the engine defaults.
 */
@Service
@Slf4j
public class ProcessRetentionService {

    final ProcessPurgeRepository purgeRepository;
    final WorkflowDefinitionRepository definitionRepository;
    final WorkflowMetrics workflowMetrics;
    final ObjectProvider<ProcessIndexRepository> processIndex;
    final ObjectProvider<ProcessTrace> processTrace;
    final ProcessRetentionPolicy policy;
    final int batchSize;
    final int maxPerPass;

    public ProcessRetentionService(ProcessPurgeRepository purgeRepository,
                                   WorkflowDefinitionRepository definitionRepository,
                                   WorkflowMetrics workflowMetrics,
                                   ObjectProvider<ProcessIndexRepository> processIndex,
                                   ObjectProvider<ProcessTrace> processTrace,
                                   ProcessRetentionProperties properties) {
        this.purgeRepository = purgeRepository;
        this.definitionRepository = definitionRepository;
        this.workflowMetrics = workflowMetrics;
        this.processIndex = processIndex;
        this.processTrace = processTrace;
        this.policy = ProcessRetentionPolicy.of(properties);
        this.batchSize = Math.max(1, properties.getPurge().getBatchSize());
        this.maxPerPass = Math.max(1, properties.getPurge().getMaxPerPass());
    }

    /** What one pass did: processes deleted per definition and status, and trees it had to keep. */
    public record PurgeReport(Map<String, Map<ProcessStatus, Integer>> purged, int treesKept) {
        public int total() {
            return purged.values().stream().flatMap(m -> m.values().stream()).mapToInt(Integer::intValue).sum();
        }
    }

    /** The retention policy this engine runs with — for the console. */
    public ProcessRetentionPolicy policy() {
        return policy;
    }

    /** One pass: every definition, every terminal status, up to the per-pass budget. */
    public PurgeReport purgeDue(LocalDateTime now) {
        var definitions = definitionRepository.findAll().stream()
                .filter(d -> d.id() != null)
                .collect(Collectors.toMap(WorkflowDefinition::id, Function.identity(), (a, b) -> a));
        var ids = new TreeSet<>(definitions.keySet());
        ids.addAll(purgeRepository.knownWorkflowDefinitionIds());

        var purged = new LinkedHashMap<String, Map<ProcessStatus, Integer>>();
        var kept = 0;
        var budget = maxPerPass;
        for (var definitionId : ids) {
            var definition = definitions.get(definitionId);
            for (var status : ProcessRetention.TERMINAL) {
                if (budget <= 0) {
                    break;
                }
                var retention = policy.retentionFor(definition, status);
                if (retention.isEmpty()) {
                    continue;
                }
                var cutoff = now.minus(retention.get());
                PurgeCandidate after = null;
                while (budget > 0) {
                    var page = purgeRepository.findRoots(definitionId, status, cutoff, after, batchSize);
                    for (var root : page) {
                        after = root;
                        var tree = treeOf(root);
                        if (tree != null && purgeRepository.purge(tree)) {
                            forgetElsewhere(tree);
                            tree.forEach(p -> purged.computeIfAbsent(p.workflowDefinitionId(), k -> new LinkedHashMap<>())
                                    .merge(p.status(), 1, Integer::sum));
                            budget -= tree.size();
                        } else {
                            kept++;
                        }
                        if (budget <= 0) {
                            break;
                        }
                    }
                    if (page.size() < batchSize) {
                        break;
                    }
                }
            }
        }
        purged.forEach((definitionId, byStatus) -> byStatus.forEach((status, count) ->
                workflowMetrics.processesPurged(definitionId, status, count)));
        return new PurgeReport(purged, kept);
    }

    /** The root and all its descendants, or null when any of them has not finished. */
    List<PurgeCandidate> treeOf(PurgeCandidate root) {
        var tree = new ArrayList<PurgeCandidate>();
        tree.add(root);
        var level = List.of(root.id());
        while (!level.isEmpty()) {
            var children = purgeRepository.findChildren(level);
            for (var child : children) {
                if (!ProcessRetention.isTerminal(child.status()) || child.finished() == null) {
                    return null;
                }
            }
            tree.addAll(children);
            level = children.stream().map(PurgeCandidate::id).toList();
        }
        return tree;
    }

    /**
     * What lives outside the store's transaction: the process index when it is a separate read
     * database, and this pod's trace-context cache. Best effort — a stale index row is a listing
     * entry that cannot be opened, not data kept.
     */
    private void forgetElsewhere(List<PurgeCandidate> tree) {
        var ids = tree.stream().map(PurgeCandidate::id).toList();
        try {
            processIndex.ifAvailable(index -> index.deleteByProcessIds(ids));
        } catch (RuntimeException e) {
            log.warn("Purged processes {} but could not remove them from the process index", ids, e);
        }
        try {
            processTrace.ifAvailable(trace -> trace.forget(ids));
        } catch (RuntimeException e) {
            log.debug("Could not forget the trace contexts of purged processes {}", ids, e);
        }
    }
}
