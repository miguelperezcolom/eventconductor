package io.mateu.workflowbench.suite;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads what the engine recorded — never what the driver assumes. Every time the report uses
 * (when a process finished, when a timer was due and when it fired) is the engine's own.
 */
interface Probe {


    record ProcessRow(String businessKey, String status, LocalDateTime created, LocalDateTime finished) {
    }

    record StepRow(String businessKey, String stepId, String status,
                   LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime deadlineAt) {
    }

    /**
     * Processes whose business key starts with {@code prefix} and whose status is one of
     * {@code statuses}. Callers name the status they expect rather than "anything final": a saga
     * passes through {@code ERROR} on its way to {@code COMPENSATED}, and counting it done there
     * would stop the clock while its compensations are still running.
     */
    long countIn(String prefix, Set<String> statuses);

    /** Outbox rows not yet relayed — work the engine has committed to but not done. */
    long pendingOutbox();

    /** Live ({@code PENDING}) executions of {@code stepId} in processes whose key starts with {@code prefix}. */
    long countPending(String prefix, String stepId);

    List<ProcessRow> processes(String prefix);

    List<StepRow> steps(String prefix, String stepId);

    /** Every step execution of those processes — the transitions the engine actually made. */
    long stepExecutions(String prefix);

    /** Removes a finished run so the next repetition starts from the same state. */
    void purge(String prefix);

    /** In-memory persistence: the engine's own repositories, read in this JVM. */
    record Memory(ProcessRepository processes, StepExecutionRepository steps) implements Probe {

        private List<Process> matching(String prefix) {
            return processes.findAll().stream()
                    .filter(p -> p.getBusinessKey() != null && p.getBusinessKey().startsWith(prefix))
                    .toList();
        }

        private Map<String, String> keysById(String prefix) {
            return matching(prefix).stream().collect(Collectors.toMap(Process::getId, Process::getBusinessKey));
        }

        @Override
        public long countIn(String prefix, Set<String> statuses) {
            return matching(prefix).stream().filter(p -> statuses.contains(p.getStatus().name())).count();
        }

        @Override
        public long pendingOutbox() {
            return 0; // memory mode has no outbox: it dispatches on the caller's thread
        }

        @Override
        public long countPending(String prefix, String stepId) {
            var ids = keysById(prefix);
            return steps.findAll().stream()
                    .filter(s -> ids.containsKey(s.getProcessId()))
                    .filter(s -> stepId.equals(s.getStepId()) && "PENDING".equals(s.getStatus().name()))
                    .count();
        }

        @Override
        public List<ProcessRow> processes(String prefix) {
            return matching(prefix).stream()
                    .map(p -> new ProcessRow(p.getBusinessKey(), p.getStatus().name(), p.getCreated(), p.getFinished()))
                    .toList();
        }

        @Override
        public List<StepRow> steps(String prefix, String stepId) {
            var ids = keysById(prefix);
            return steps.findAll().stream()
                    .filter(s -> ids.containsKey(s.getProcessId()) && stepId.equals(s.getStepId()))
                    .map(s -> row(ids.get(s.getProcessId()), s))
                    .toList();
        }

        private static StepRow row(String key, StepExecution s) {
            return new StepRow(key, s.getStepId(), s.getStatus().name(), s.getStartedAt(), s.getFinishedAt(),
                    s.getDeadlineAt());
        }

        @Override
        public long stepExecutions(String prefix) {
            var ids = keysById(prefix);
            return steps.findAll().stream().filter(s -> ids.containsKey(s.getProcessId())).count();
        }

        @Override
        public void purge(String prefix) {
            var ids = keysById(prefix);
            steps.deleteAllById(steps.findAll().stream()
                    .filter(s -> ids.containsKey(s.getProcessId())).map(StepExecution::id).toList());
            processes.deleteAllById(List.copyOf(ids.keySet()));
        }
    }

    /** JPA persistence (embedded/jpa and the kafka modes): straight to PostgreSQL. */
    record Jdbc(JdbcTemplate jdbc) implements Probe {

        @Override
        public long countIn(String prefix, Set<String> statuses) {
            var in = String.join(",", statuses.stream().map(st -> "'" + st + "'").toList());
            return jdbc.queryForObject("SELECT count(*) FROM process_entity WHERE business_key LIKE ? AND status IN ("
                    + in + ")", Long.class, prefix + "%");
        }

        @Override
        public long pendingOutbox() {
            return jdbc.queryForObject("SELECT count(*) FROM outbox_message_entity WHERE status = 'Pending'", Long.class);
        }

        @Override
        public long countPending(String prefix, String stepId) {
            return jdbc.queryForObject("SELECT count(*) FROM step_execution_entity s JOIN process_entity p"
                    + " ON p.id = s.process_id WHERE p.business_key LIKE ? AND s.step_id = ? AND s.status = 'PENDING'",
                    Long.class, prefix + "%", stepId);
        }

        @Override
        public List<ProcessRow> processes(String prefix) {
            return jdbc.query("SELECT business_key, status, created, finished FROM process_entity WHERE business_key LIKE ?",
                    (rs, i) -> new ProcessRow(rs.getString(1), rs.getString(2),
                            rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class)),
                    prefix + "%");
        }

        @Override
        public List<StepRow> steps(String prefix, String stepId) {
            return jdbc.query("SELECT p.business_key, s.step_id, s.status, s.started_at, s.finished_at, s.deadline_at"
                            + " FROM step_execution_entity s JOIN process_entity p ON p.id = s.process_id"
                            + " WHERE p.business_key LIKE ? AND s.step_id = ?",
                    (rs, i) -> new StepRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class),
                            rs.getObject(6, LocalDateTime.class)),
                    prefix + "%", stepId);
        }

        @Override
        public long stepExecutions(String prefix) {
            return jdbc.queryForObject("SELECT count(*) FROM step_execution_entity s JOIN process_entity p"
                    + " ON p.id = s.process_id WHERE p.business_key LIKE ?", Long.class, prefix + "%");
        }

        @Override
        public void purge(String prefix) {
            jdbc.update("DELETE FROM step_execution_entity WHERE process_id IN"
                    + " (SELECT id FROM process_entity WHERE business_key LIKE ?)", prefix + "%");
            jdbc.update("DELETE FROM process_entity WHERE business_key LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM outbox_message_entity WHERE status = 'Sent'");
            jdbc.execute("VACUUM ANALYZE");
        }
    }
}
