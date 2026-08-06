package io.mateu.workflow.application.readmodel;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read side of CQRS: the query API over the process-index read model. Every "what is running",
 * "find this process", "how many are stuck" question answers from here — a single-table read — never
 * by scanning the write-side {@code process_entity}/{@code step_execution_entity} tables.
 *
 * <p>Always wired (the read store adapter exists regardless of {@code workflow.projection.enabled}),
 * but the index is only populated while the projector is on. With it off these queries return empty:
 * the read model is opt-in, and nothing writes to it until it is enabled.
 */
@Service
@RequiredArgsConstructor
public class ProcessIndexQueryService {

    /** The non-terminal statuses — a process in any of these is still in flight. */
    private static final List<String> IN_FLIGHT = List.of(
            ProcessStatus.PENDING.name(),
            ProcessStatus.RUNNING.name(),
            ProcessStatus.PAUSED.name());

    private final ProcessIndexRepository processIndexRepository;

    /** Every process still in flight (PENDING / RUNNING / PAUSED) — the live operational view. */
    public List<ProcessIndexRow> findInFlight() {
        return processIndexRepository.findByStatusIn(IN_FLIGHT);
    }

    /** Same, scoped to one workflow definition. */
    public List<ProcessIndexRow> findInFlightByDefinition(String workflowDefinitionId) {
        return processIndexRepository.findByWorkflowDefinitionIdAndStatusIn(workflowDefinitionId, IN_FLIGHT);
    }

    /** Every process in exactly the given statuses (e.g. the terminal failures to triage). */
    public List<ProcessIndexRow> findByStatuses(List<ProcessStatus> statuses) {
        return processIndexRepository.findByStatusIn(statuses.stream().map(ProcessStatus::name).toList());
    }

    /** Point lookup by business key — the process's stable, unique routing key. */
    public Optional<ProcessIndexRow> findByBusinessKey(String businessKey) {
        return processIndexRepository.findByBusinessKey(businessKey);
    }

    /** How many processes sit in each status — the operator's at-a-glance fleet counts. */
    public Map<String, Long> countByStatus() {
        return processIndexRepository.countByStatus();
    }
}
