package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.IncidentRepository;
import io.mateu.workflow.application.out.IncidentRepository.FailedStep;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.worker.FailureMarkers;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Incidents: the failures waiting for an operator, grouped so that one cause reads as one line — the
 * view Camunda Operate calls incidents.
 *
 * <p>The rows are the failed steps of failed processes ({@link IncidentRepository}). They are grouped by
 * <b>(definition, step, error family)</b>: the same step of the same workflow failing with the same kind
 * of message is one incident, however many processes it stopped. The family is the step's last error
 * line with what varies from one occurrence to the next taken out ({@link #family}) — ids, numbers,
 * quoted values — so "Payment 4711 declined for 'ACME'" and "Payment 4712 declined for 'Initech'" are
 * one family, and a timeout is not the same incident as a declined card.
 *
 * <p>Each group lists how many steps and processes it holds and when it was first and last seen; its
 * members are the drill-down; and the whole group can be retried at once ({@link #retryGroup}).
 *
 * <p><b>Bounded.</b> The grouping reads at most {@code workflow.incidents.scan-limit} failed steps, the
 * most recent first, and says when it stopped there ({@link Incidents#truncated()}). A group retry
 * re-drives at most {@code workflow.incidents.retry.max-steps} of its steps; beyond
 * {@code workflow.incidents.retry.sync-limit} it does so in the background, one step after another,
 * and answers at once. That loop is deliberately minimal — the first thing a batch-operations facility
 * replaces.
 */
@Service
@Slf4j
public class IncidentService {

    /** The family of a failure that logged nothing. */
    public static final String NO_MESSAGE = "(no error message)";

    static final int MAX_FAMILY_LENGTH = 160;

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b(?=[0-9a-fA-F]*[0-9])[0-9a-fA-F]{12,}\\b");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'[^']*'");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"]*\"");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:]\\d+)*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** One incident: a step of a definition failing with one family of error. */
    public record IncidentGroup(String key, String workflowDefinitionId, String stepId, String errorFamily,
                                String sampleError, int failedSteps, int processes,
                                LocalDateTime firstSeen, LocalDateTime lastSeen) {
    }

    /**
     * The incidents, most failures first.
     *
     * @param scanned   how many failed steps were read
     * @param truncated whether there were more than the scan limit — the counts are then of the most recent
     */
    public record Incidents(List<IncidentGroup> groups, int scanned, boolean truncated) {
    }

    /**
     * What a group retry did.
     *
     * @param requested how many step retries were (or are being) requested
     * @param async     whether they are being requested in the background
     * @param truncated whether the group held more steps than one retry re-drives
     */
    public record RetryOutcome(String key, int requested, boolean async, boolean truncated) {
    }

    private final IncidentRepository incidentRepository;
    private final CommandDispatcher commandDispatcher;
    private final ProcessOperationsService processOperations;
    private final OperatorAudit audit;
    private final int scanLimit;
    private final int retryMaxSteps;
    private final int retrySyncLimit;
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "eventconductor-incident-retry");
        thread.setDaemon(true);
        return thread;
    });

    public IncidentService(IncidentRepository incidentRepository,
                           CommandDispatcher commandDispatcher,
                           ProcessOperationsService processOperations,
                           OperatorAudit audit,
                           @Value("${workflow.incidents.scan-limit:10000}") int scanLimit,
                           @Value("${workflow.incidents.retry.max-steps:1000}") int retryMaxSteps,
                           @Value("${workflow.incidents.retry.sync-limit:50}") int retrySyncLimit) {
        this.incidentRepository = incidentRepository;
        this.commandDispatcher = commandDispatcher;
        this.processOperations = processOperations;
        this.audit = audit;
        this.scanLimit = Math.max(1, scanLimit);
        this.retryMaxSteps = Math.max(1, retryMaxSteps);
        this.retrySyncLimit = Math.max(0, retrySyncLimit);
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    /**
     * The incidents, grouped; every definition's when {@code workflowDefinitionId} is null.
     */
    public Incidents incidents(String workflowDefinitionId) {
        InputLimits.checkIdentifier(workflowDefinitionId, "workflowDefinitionId");
        var rows = incidentRepository.findFailedSteps(blankToNull(workflowDefinitionId), scanLimit + 1);
        var truncated = rows.size() > scanLimit;
        if (truncated) {
            rows = rows.subList(0, scanLimit);
        }
        var byKey = new LinkedHashMap<String, List<FailedStep>>();
        for (var row : rows) {
            byKey.computeIfAbsent(keyOf(row), k -> new ArrayList<>()).add(row);
        }
        var groups = byKey.entrySet().stream()
                .map(entry -> group(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(IncidentGroup::failedSteps).reversed()
                        .thenComparing(IncidentGroup::lastSeen, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
                        .thenComparing(IncidentGroup::key))
                .toList();
        return new Incidents(groups, rows.size(), truncated);
    }

    /** One incident, or null when there is no such group (any more). */
    public IncidentGroup incident(String key) {
        var members = members(key, scanLimit);
        return members.isEmpty() ? null : group(key, members);
    }

    /** The failed steps of one incident, most recent first, at most {@code limit}. */
    public List<FailedStep> members(String key, int limit) {
        InputLimits.checkIdentifier(key, "incident key");
        return incidentRepository.findFailedSteps(null, scanLimit).stream()
                .filter(row -> keyOf(row).equals(key))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Retries every failed step of one incident — each exactly as a step retry from the console does
     * ({@code RetryStepExecutionUseCase}, on the node that owns the process, under its lock), and each
     * recorded in its process's audit trail with the operator and the reason.
     *
     * @throws IllegalArgumentException no reason, or no such incident
     * @throws io.mateu.workflow.security.FlowAuthorizationDeniedException the caller may not operate on
     *                                                                     the incident's definition
     */
    public RetryOutcome retryGroup(String key, String reason, AuthorizationContext caller) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for a manual operation.");
        }
        InputLimits.checkText(reason, "an operation's reason");
        var members = members(key, retryMaxSteps + 1);
        if (members.isEmpty()) {
            throw new IncidentNotFoundException(key);
        }
        var truncated = members.size() > retryMaxSteps;
        var toRetry = truncated ? members.subList(0, retryMaxSteps) : members;
        var definitionId = toRetry.getFirst().workflowDefinitionId();
        processOperations.authorizeOperation(definitionId, caller, "retry incident '" + key + "'");
        var requestedBy = caller == null ? null : caller.subject();
        var family = family(toRetry.getFirst().error());
        var steps = List.copyOf(toRetry);
        Runnable work = () -> retryAll(steps, family, reason.strip(), requestedBy);
        var async = steps.size() > retrySyncLimit;
        if (async) {
            background.submit(work);
        } else {
            work.run();
        }
        log.info("Incident {} ({} / {} / {}): {} step retr{} requested{}", key, definitionId,
                toRetry.getFirst().stepId(), family, steps.size(), steps.size() == 1 ? "y" : "ies",
                async ? " in the background" : "");
        return new RetryOutcome(key, steps.size(), async, truncated);
    }

    private void retryAll(List<FailedStep> steps, String family, String reason, String requestedBy) {
        for (var step : steps) {
            try {
                audit.performed(step.processId(), step.workflowDefinitionId(), step.stepExecutionId(),
                        OperatorAudit.RETRY_INCIDENT, requestedBy, reason,
                        "retried step " + step.stepId() + " with the rest of its incident (" + family + ")");
                commandDispatcher.dispatch(new RetryStepExecutionRequested(step.stepExecutionId(), step.processId()));
            } catch (RuntimeException e) {
                // One process that cannot be retried now (its lock is busy, it moved on) must not stop
                // the others; the incident still lists it, to be retried again.
                log.warn("Incident retry of step execution {} of process {} failed: {}", step.stepExecutionId(),
                        step.processId(), e.getMessage());
            }
        }
    }

    private static IncidentGroup group(String key, List<FailedStep> members) {
        var first = members.getFirst();
        var processes = (int) members.stream().map(FailedStep::processId).distinct().count();
        var firstSeen = members.stream().map(FailedStep::failedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        var lastSeen = members.stream().map(FailedStep::failedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        // The most recent message stands for the family — the rows come newest first.
        return new IncidentGroup(key, first.workflowDefinitionId(), first.stepId(), family(first.error()),
                first.error(), members.size(), processes, firstSeen, lastSeen);
    }

    /** A stable, URL-safe key for (definition, step, family). */
    public static String keyOf(FailedStep row) {
        return keyOf(row.workflowDefinitionId(), row.stepId(), family(row.error()));
    }

    static String keyOf(String definitionId, String stepId, String family) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(
                    (definitionId + "\u0000" + stepId + "\u0000" + family).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The family of an error message: its first line, without the non-retryable marker, with ids,
     * numbers and quoted values replaced by placeholders, whitespace collapsed, and at most
     * {@value #MAX_FAMILY_LENGTH} characters. What is left is what the failures of one cause share.
     */
    public static String family(String message) {
        if (message == null || message.isBlank()) {
            return NO_MESSAGE;
        }
        var text = message.strip();
        if (FailureMarkers.isNonRetryable(text)) {
            text = text.substring(FailureMarkers.NON_RETRYABLE.length()).strip();
        }
        var newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).strip();
        }
        text = UUID.matcher(text).replaceAll("<id>");
        text = LONG_HEX.matcher(text).replaceAll("<id>");
        text = SINGLE_QUOTED.matcher(text).replaceAll("'…'");
        text = DOUBLE_QUOTED.matcher(text).replaceAll("\"…\"");
        text = NUMBER.matcher(text).replaceAll("#");
        text = SPACES.matcher(text).replaceAll(" ").strip();
        if (text.isEmpty()) {
            return NO_MESSAGE;
        }
        return text.length() <= MAX_FAMILY_LENGTH ? text : text.substring(0, MAX_FAMILY_LENGTH) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** No incident has this key (any more: it was resolved, or retried). */
    public static class IncidentNotFoundException extends RuntimeException {
        public IncidentNotFoundException(String key) {
            super("No incident '" + key + "'.");
        }
    }
}
