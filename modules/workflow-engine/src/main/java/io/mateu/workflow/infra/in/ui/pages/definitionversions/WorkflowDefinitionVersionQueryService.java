package io.mateu.workflow.infra.in.ui.pages.definitionversions;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.out.persistence.ProcessEntityRepository;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntity;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Assembles the per-version rows for a definition: joins the recorded version history (number +
 * creation date) with the process counts that ran with each version. Processes whose recorded
 * version number matches no history row (created before versioning existed) are folded into a single
 * "legacy" row so they stay visible without a data migration.
 *
 * <p>JPA-only, like the rest of the versioning feature — it reads the version history and process
 * tables directly.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionVersionQueryService {

    /** Marker version id for the aggregated pre-versioning bucket. */
    public static final String LEGACY = "legacy";

    private final WorkflowDefinitionVersionEntityRepository versions;
    private final ProcessEntityRepository processes;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Live process counts per version. */
    private record Counts(long running, long completed, long total) {
        static Counts EMPTY = new Counts(0, 0, 0);
        Counts plus(String status, long n) {
            var ps = ProcessStatus.valueOf(status);
            boolean isRunning = ps == ProcessStatus.PENDING || ps == ProcessStatus.RUNNING || ps == ProcessStatus.PAUSED;
            boolean isCompleted = ps == ProcessStatus.COMPLETED;
            return new Counts(running + (isRunning ? n : 0), completed + (isCompleted ? n : 0), total + n);
        }
    }

    public List<VersionRow> rows(String definitionId) {
        // Aggregate process counts by the version number they ran with.
        var byVersion = new LinkedHashMap<Integer, Counts>();
        for (var c : processes.countByVersionAndStatus(definitionId)) {
            int v = Integer.parseInt(c.getKey());
            byVersion.merge(v, Counts.EMPTY.plus(c.getStatus(), c.getCount()),
                    (a, b) -> new Counts(a.running() + b.running(), a.completed() + b.completed(), a.total() + b.total()));
        }

        var recorded = versions.findByDefinitionIdOrderByVersionDesc(definitionId);
        Set<Integer> recordedNumbers = new HashSet<>();
        recorded.forEach(v -> recordedNumbers.add(v.getVersion()));

        var rows = new ArrayList<VersionRow>();
        for (WorkflowDefinitionVersionEntity v : recorded) {
            var counts = byVersion.getOrDefault(v.getVersion(), Counts.EMPTY);
            rows.add(new VersionRow(
                    definitionId + ":" + v.getVersion(),
                    "v" + v.getVersion(),
                    v.getCreatedAt() != null ? v.getCreatedAt().format(dtf) : "—",
                    counts.running(), counts.completed(), counts.total()));
        }

        // Fold every process version number that has no history row into one "legacy" bucket.
        var legacy = Counts.EMPTY;
        boolean anyLegacy = false;
        for (var e : byVersion.entrySet()) {
            if (recordedNumbers.contains(e.getKey())) continue;
            anyLegacy = true;
            var c = e.getValue();
            legacy = new Counts(legacy.running() + c.running(),
                    legacy.completed() + c.completed(), legacy.total() + c.total());
        }
        if (anyLegacy) {
            rows.add(new VersionRow(definitionId + ":" + LEGACY, "legacy (pre-versioning)", "—",
                    legacy.running(), legacy.completed(), legacy.total()));
        }
        return rows;
    }
}
