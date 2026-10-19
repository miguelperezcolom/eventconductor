package io.mateu.workflow.application.updates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.UpdateDefinition;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.AgentSchemas;
import io.mateu.workflow.domain.services.UpdateEvaluator;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.ProcessUpdateRequested;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.security.FlowAuthorizationService;
import io.mateu.workflow.time.EngineClock;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The door of process updates: checks what can be checked without the process lock, records the update,
 * hands it to the node that owns the process ({@link ApplyProcessUpdateUseCase}) and lets the caller wait —
 * up to a deadline — for its outcome. Also update-with-start.
 *
 * <p>Idempotent by {@code (process, idempotency key)}: a retry gets the recorded update — its outcome, if
 * it has one — and never a second application. A retry of an update still {@code REQUESTED} re-sends the
 * command (the owner ignores it if it already applied it), which is the re-drive for a command that was
 * lost on the way.
 */
@Service
@Slf4j
public class ProcessUpdateService {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    final ProcessRepository processRepository;
    final ProcessUpdateRepository updateRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final CreateProcessUseCase createProcessUseCase;
    final CommandDispatcher commandDispatcher;
    final ApplyProcessUpdateUseCase applyProcessUpdateUseCase;
    final ProcessUpdateWaiters waiters;
    final LogMessageRepository logMessageRepository;
    final WorkflowMetrics workflowMetrics;
    final ObjectProvider<UpdateWithStartPolicy> policy;

    @Value("${workflow.mode:embedded}")
    String mode = "embedded";

    @Value("${workflow.sharding.enabled:false}")
    boolean sharding;

    @Value("${workflow.security.flow-authorization.enabled:false}")
    boolean flowAuthorizationEnabled;

    @Value("${workflow.sync.default-deadline-ms:5000}")
    long defaultDeadlineMs = 5000;

    @Value("${workflow.sync.max-deadline-ms:30000}")
    long maxDeadlineMs = 30000;

    @Value("${workflow.sync.retention:PT24H}")
    Duration retention = Duration.ofHours(24);

    @Value("${workflow.sync.max-reply-bytes:262144}")
    long maxResultBytes = 262_144;

    /**
     * Keys the request fingerprint (an HMAC under a key derived from the active one, never a plain digest a
     * card number could be guessed back from), seals the payload fields that must not be kept or carried in
     * clear, opens a sensitive result for its caller.
     */
    io.mateu.workflow.crypto.VariableEncryption encryption = io.mateu.workflow.crypto.VariableEncryption.DISABLED;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void encryption(io.mateu.workflow.crypto.VariableEncryption encryption) {
        this.encryption = encryption == null ? io.mateu.workflow.crypto.VariableEncryption.DISABLED : encryption;
    }

    /** Where an embedded node applies updates: off the request thread, so a deadline can be honoured. */
    private final ExecutorService applier = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("process-update-", 0).factory());

    public ProcessUpdateService(ProcessRepository processRepository, ProcessUpdateRepository updateRepository,
                                WorkflowDefinitionRepository workflowDefinitionRepository,
                                CreateProcessUseCase createProcessUseCase, CommandDispatcher commandDispatcher,
                                ApplyProcessUpdateUseCase applyProcessUpdateUseCase, ProcessUpdateWaiters waiters,
                                LogMessageRepository logMessageRepository, WorkflowMetrics workflowMetrics,
                                ObjectProvider<UpdateWithStartPolicy> policy) {
        this.processRepository = processRepository;
        this.updateRepository = updateRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.createProcessUseCase = createProcessUseCase;
        this.commandDispatcher = commandDispatcher;
        this.applyProcessUpdateUseCase = applyProcessUpdateUseCase;
        this.waiters = waiters;
        this.logMessageRepository = logMessageRepository;
        this.workflowMetrics = workflowMetrics;
        this.policy = policy;
    }

    @PreDestroy
    void stop() {
        applier.shutdown();
    }

    /**
     * An update of a live process. {@code processId} or {@code businessKey} names it; {@code payload} is
     * JSON text (null means none); {@code idempotencyKey} is optional (none: no retry dedupe);
     * {@code requestedWait} null means the engine default.
     */
    public record UpdateRequest(String processId, String businessKey, String updateName, String payload,
                                String idempotencyKey, Duration requestedWait, AuthorizationContext caller) {
    }

    /** An update-with-start: the process with {@code businessKey}, or a new one with {@code variables}. */
    public record StartRequest(String workflowDefinitionId, String businessKey, Map<String, String> variables,
                               String updateName, String payload, String idempotencyKey, Duration requestedWait,
                               AuthorizationContext caller) {
    }

    /** The update a request resolved to, whether this request recorded it, and how long to wait for it. */
    public record Submitted(ProcessUpdate update, boolean created, Duration timeToWait) {
    }

    // ── Update ─────────────────────────────────────────────────────────────────────────────────────

    /** Records the update and sends it to the process's owner; the caller then {@link #await}s it. */
    public Submitted submit(UpdateRequest request) {
        return submit(request, false);
    }

    /**
     * {@link #submit(UpdateRequest)}, applying the update on the calling thread when this node owns every
     * process (embedded mode) — what the test kit and other synchronous callers want.
     */
    public Submitted submitInline(UpdateRequest request) {
        return submit(request, true);
    }

    private Submitted submit(UpdateRequest request, boolean inline) {
        checkIdentifiers(request.updateName(), request.idempotencyKey(), request.payload());
        var process = resolve(request.processId(), request.businessKey());
        var definition = ApplyProcessUpdateUseCase.definitionOf(process);
        authorize(definition, process.getWorkflowDefinitionId(), request.caller(), "update process '" + process.getId() + "'");
        var handler = (definition == null ? Optional.<UpdateDefinition>empty() : definition.update(request.updateName()))
                .orElseThrow(() -> new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE,
                        "Process '" + process.getId() + "' (" + process.getWorkflowDefinitionId() + " v"
                                + process.getWorkflowDefinitionVersion() + ") declares no update '" + request.updateName() + "'."));
        var wait = waitFor(request.requestedWait());
        var canonical = canonicalOf(request.updateName(), request.payload());
        var hash = encryption.fingerprint(canonical);
        var sensitive = io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process);
        if (request.idempotencyKey() != null) {
            var existing = updateRepository.findByKey(process.getId(), request.idempotencyKey());
            if (existing.isPresent()) {
                var retried = retried(existing.get(), canonical, wait, inline);
                // Still REQUESTED: the command may have been lost on the way (a node that died, a broker
                // that refused it) — send it again. The owner applies an update once, so this is harmless.
                redrive(retried.update(), carried(handler, request.payload(), sensitive), inline);
                return retried;
            }
        }
        var problems = payloadProblems(handler, request.payload());
        if (!problems.isEmpty()) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD,
                    "The payload of update '" + handler.name() + "' is not valid: " + String.join("; ", problems));
        }
        // A cheap look before recording anything: a process that is plainly not accepting is answered now.
        // The owner decides for good under the lock — this is only the common case of a finished process.
        if (!ApplyProcessUpdateUseCase.ACCEPTING.contains(process.getStatus())) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NOT_ACCEPTING,
                    "The process is " + process.getStatus() + "; updates are accepted while it is RUNNING or PENDING.");
        }
        var id = UUID.randomUUID().toString();
        var now = EngineClock.now();
        var update = new ProcessUpdate(id, process.getId(), process.getWorkflowDefinitionId(), handler.name(),
                request.idempotencyKey() == null ? id : request.idempotencyKey(), hash,
                recorded(handler, request.payload(), sensitive, encryption), ProcessUpdate.Status.REQUESTED, null, null,
                subject(request.caller()), false, now, null, now.plus(retention));
        try {
            updateRepository.insert(update);
        } catch (ProcessUpdateRepository.DuplicateUpdateException e) {
            // A concurrent retry took the key between the lookup and the insert: theirs is the update.
            var winner = updateRepository.findByKey(process.getId(), update.idempotencyKey()).orElseThrow(() -> e);
            return retried(winner, canonical, wait, inline);
        }
        log.info("Update '{}' {} of process {} requested", handler.name(), id, process.getId());
        dispatch(update, carried(handler, request.payload(), sensitive), inline);
        return new Submitted(updateRepository.findById(id).orElse(update), true, wait);
    }

    private Submitted retried(ProcessUpdate existing, String canonical, Duration wait, boolean inline) {
        // Verified under the key the stored fingerprint names: a key rotation inside the update's retention
        // does not turn a genuine retry into a "key reused" refusal.
        if (existing.requestHash() != null && !encryption.fingerprintMatches(existing.requestHash(), canonical)) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key '" + existing.idempotencyKey() + "' was already used for a different update.");
        }
        log.info("Update {} of process {} retried with its key", existing.id(), existing.processId());
        return new Submitted(existing, false, wait);
    }

    /**
     * Re-sends a still-{@code REQUESTED} update's command, with the payload of a retry — the re-drive for a
     * command that never reached the owner. Harmless when it did: the owner applies an update once.
     */
    public void redrive(ProcessUpdate update, String payload, boolean inline) {
        if (update.status() == ProcessUpdate.Status.REQUESTED) {
            dispatch(update, payload, inline);
        }
    }

    // ── Update-with-start ──────────────────────────────────────────────────────────────────────────

    /** The outcome of an update-with-start: the update, and whether it started its process. */
    public record StartSubmitted(ProcessUpdate update, boolean started, Duration timeToWait) {
    }

    /**
     * Delivers the update to the process with the business key — starting it first when there is none.
     * The start and the update are one transaction: the update is evaluated against the new process's
     * initial variables, its {@code set} seeds them, and the process is created with its update already
     * COMPLETED — the first thing it sees, before its first step. A refused update starts nothing.
     */
    public StartSubmitted updateWithStart(StartRequest request) {
        return updateWithStart(request, false);
    }

    /** {@link #updateWithStart(StartRequest)}, applying an update to an existing process inline. */
    public StartSubmitted updateWithStartInline(StartRequest request) {
        return updateWithStart(request, true);
    }

    private StartSubmitted updateWithStart(StartRequest request, boolean inline) {
        if (request.businessKey() == null || request.businessKey().isBlank()) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD,
                    "An update-with-start needs a businessKey: it is how a retry, or a second caller, finds the process.");
        }
        checkIdentifiers(request.updateName(), request.idempotencyKey(), request.payload());
        InputLimits.checkIdentifier(request.businessKey(), "businessKey");
        var initial = request.variables() == null ? List.<Variable>of() : request.variables().entrySet().stream()
                .map(entry -> new Variable(entry.getKey(), entry.getValue())).toList();
        InputLimits.checkNamedValues(initial, Variable::name, Variable::value, "an update-with-start");
        var definition = workflowDefinitionRepository.findById(request.workflowDefinitionId())
                .orElseThrow(() -> new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE,
                        "No workflow definition '" + request.workflowDefinitionId() + "'."));
        var handler = definition.update(request.updateName())
                .orElseThrow(() -> new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE,
                        "Workflow '" + definition.id() + "' declares no update '" + request.updateName() + "'."));
        for (int attempt = 0; ; attempt++) {
            var resolution = policy.getIfAvailable(() -> new DefaultUpdateWithStartPolicy(processRepository))
                    .resolve(definition, request.businessKey());
            switch (resolution) {
                case UpdateWithStartPolicy.Conflict conflict -> throw new ProcessUpdateRejectedException(
                        ProcessUpdateRejectedException.Reason.BUSINESS_KEY_TAKEN, conflict.why());
                case UpdateWithStartPolicy.Existing existing -> {
                    var submitted = submit(new UpdateRequest(existing.process().getId(), null, request.updateName(),
                            request.payload(), request.idempotencyKey(), request.requestedWait(), request.caller()), inline);
                    return new StartSubmitted(submitted.update(), submitted.update().withStart(), submitted.timeToWait());
                }
                case UpdateWithStartPolicy.Start start -> {
                    var started = start(definition, handler, initial, request);
                    if (started != null) {
                        return started;
                    }
                    if (attempt > 0) {
                        throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NOT_ACCEPTING,
                                "The process for business key '" + request.businessKey() + "' could not be started.");
                    }
                    // The key was taken by a concurrent start between the policy's look and the creation:
                    // resolve again, which now finds that process.
                }
            }
        }
    }

    /** Starts the process with its update applied; null when a concurrent start took the key first. */
    private StartSubmitted start(WorkflowDefinition definition, UpdateDefinition handler, List<Variable> initial,
                                 StartRequest request) {
        authorize(definition, definition.id(), request.caller(), "start '" + definition.id() + "' with an update");
        if (!definition.status().accceptsNewInstances()) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NOT_ACCEPTING,
                    "Workflow '" + definition.id() + "' is " + definition.status() + " and accepts no new instances.");
        }
        var problems = payloadProblems(handler, request.payload());
        if (!problems.isEmpty()) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD,
                    "The payload of update '" + handler.name() + "' is not valid: " + String.join("; ", problems));
        }
        var processId = UUID.randomUUID().toString();
        var candidate = Process.builder().id(processId).name(definition.name())
                .workflowDefinitionId(definition.id()).workflowDefinitionVersion(definition.version())
                .businessKey(request.businessKey()).variables(new ArrayList<>(initial))
                .status(ProcessStatus.PENDING).build();
        var sensitive = io.mateu.workflow.application.services.SensitiveVariablePolicies.of(definition);
        var outcome = UpdateEvaluator.evaluate(handler, candidate, UpdateEvaluator.payloadOf(request.payload()), maxResultBytes,
                sensitive.isEmpty() ? null : sensitive);
        var applied = switch (outcome) {
            case UpdateEvaluator.Refused refused -> throw new ProcessUpdateRejectedException(
                    ProcessUpdateRejectedException.Reason.REFUSED, refused.message());
            case UpdateEvaluator.Failed failed -> throw new ProcessUpdateRejectedException(
                    ProcessUpdateRejectedException.Reason.FAILED, failed.message());
            case UpdateEvaluator.Applied ok -> ok;
        };
        var variables = new ArrayList<>(initial);
        applied.variables().forEach(variable -> {
            variables.removeIf(existing -> existing.name().equals(variable.name()));
            variables.add(variable);
        });
        var id = UUID.randomUUID().toString();
        var now = EngineClock.now();
        var update = new ProcessUpdate(id, processId, definition.id(), handler.name(),
                request.idempotencyKey() == null ? id : request.idempotencyKey(),
                encryption.fingerprint(canonicalOf(request.updateName(), request.payload())),
                recorded(handler, request.payload(), sensitive, encryption), ProcessUpdate.Status.COMPLETED,
                !sensitive.isEmpty() && handler.allowsSensitiveVariables()
                        ? ApplyProcessUpdateUseCase.sealedResult(applied.result(), encryption) : applied.result(),
                null, subject(request.caller()), true, now, now,
                now.plus(retention));
        var created = new boolean[1];
        updateRepository.inTransaction(() -> {
            createProcessUseCase.handle(new CreateProcessCommand(processId, definition.id(), request.businessKey(),
                    variables, null, request.caller()));
            if (processRepository.findById(processId).isEmpty()) {
                return; // declined: the key was taken concurrently (or the definition switched off under us)
            }
            updateRepository.insert(update);
            logMessageRepository.save(new LogMessage(UUID.randomUUID().toString(), now, processId, null,
                    MessageType.Info.name(), "Update '" + handler.name() + "' applied with the start"
                    + (update.requestedBy() == null ? "" : " (by " + update.requestedBy() + ")"), "update"));
            created[0] = true;
        });
        if (!created[0]) {
            return null;
        }
        workflowMetrics.processUpdateFinished(definition.id(), handler.name(), ProcessUpdate.Status.COMPLETED.name());
        log.info("Update-with-start '{}' started process {} (key '{}')", handler.name(), processId, request.businessKey());
        return new StartSubmitted(update, true, waitFor(request.requestedWait()));
    }

    // ── Reads and waiting ──────────────────────────────────────────────────────────────────────────

    /** Waits up to {@code wait} for the update's outcome; completes with the update as it stands then. */
    public CompletableFuture<ProcessUpdate> await(ProcessUpdate update, Duration wait) {
        if (update.isFinal()) {
            return CompletableFuture.completedFuture(update);
        }
        return waiters.await(update.id(), wait)
                .thenApply(found -> found.orElseGet(() -> updateRepository.findById(update.id()).orElse(update)));
    }

    public Optional<ProcessUpdate> find(String updateId, AuthorizationContext caller) {
        var update = updateRepository.findById(updateId);
        update.ifPresent(found -> authorize(null, found.workflowDefinitionId(), caller, "read update '" + updateId + "'"));
        return update;
    }

    /** The updates of a process, oldest first. */
    public List<ProcessUpdate> list(String processId, AuthorizationContext caller) {
        var process = resolve(processId, null);
        authorize(null, process.getWorkflowDefinitionId(), caller, "read the updates of process '" + processId + "'");
        return updateRepository.findByProcessId(process.getId());
    }

    /** The wait a caller gets: what it asked for, else the engine's default — capped. */
    public Duration waitFor(Duration requested) {
        var millis = requested != null ? requested.toMillis() : defaultDeadlineMs;
        return Duration.ofMillis(Math.max(0, Math.min(millis, maxDeadlineMs)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────

    private void dispatch(ProcessUpdate update, String payload, boolean inline) {
        var command = new ProcessUpdateRequested(update.id(), update.processId(), update.name(), payload,
                update.requestedBy());
        if (sharding || "kafka".equals(mode)) {
            try {
                commandDispatcher.dispatch(command);
            } catch (RuntimeException e) {
                log.error("Could not send update {} of process {} to its owner", update.id(), update.processId(), e);
                throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.UNAVAILABLE,
                        "The update was recorded but could not be sent to the process; retry with the same"
                                + " Idempotency-Key.", update);
            }
            return;
        }
        if (inline) {
            applyProcessUpdateUseCase.handle(command);
            return;
        }
        applier.execute(() -> applyWithRetries(command));
    }

    /** Embedded: a busy process lock is retried a few times; still busy, the update stays REQUESTED for a retry. */
    private void applyWithRetries(ProcessUpdateRequested command) {
        for (int attempt = 1; ; attempt++) {
            try {
                applyProcessUpdateUseCase.handle(command);
                return;
            } catch (io.mateu.workflow.application.out.ConcurrentProcessAccessException e) {
                if (attempt >= 3) {
                    log.warn("Update {} of process {} not applied: the process stayed busy. A retry with its key"
                            + " re-sends it.", command.updateId(), command.processId());
                    return;
                }
            } catch (RuntimeException e) {
                log.error("Update {} of process {} could not be applied", command.updateId(), command.processId(), e);
                return;
            }
        }
    }

    private Process resolve(String processId, String businessKey) {
        Optional<Process> process = processId != null && !processId.isBlank()
                ? processRepository.findById(processId)
                : businessKey != null && !businessKey.isBlank() ? processRepository.findByBusinessKey(businessKey) : Optional.empty();
        return process.orElseThrow(() -> new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NO_SUCH_PROCESS,
                processId != null && !processId.isBlank() ? "No process with id '" + processId + "'."
                        : "No process with business key '" + businessKey + "'."));
    }

    /** The definition's flow-authorization gate, as for reading or commanding one of its processes. */
    private void authorize(WorkflowDefinition snapshot, String definitionId, AuthorizationContext caller, String what) {
        if (!flowAuthorizationEnabled || definitionId == null) {
            return;
        }
        var definition = snapshot != null ? snapshot : workflowDefinitionRepository.findById(definitionId).orElse(null);
        if (definition == null) {
            return;
        }
        var decision = FlowAuthorizationService.authorize(caller, definition.requiredScopes(), definition.requiredRoles());
        if (!decision.allowed()) {
            throw FlowAuthorizationDeniedException.of(what, caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles());
        }
    }

    private static void checkIdentifiers(String updateName, String idempotencyKey, String payload) {
        try {
            InputLimits.checkIdentifier(updateName, "the update's name");
            InputLimits.checkIdentifier(idempotencyKey, "Idempotency-Key");
            InputLimits.checkText(payload, "an update's payload");
        } catch (InputLimits.InputRejectedException e) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.INVALID_PAYLOAD, e.getMessage());
        }
        if (updateName == null || updateName.isBlank()) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.NO_SUCH_UPDATE, "No update named.");
        }
    }

    /** What is wrong with the payload: not JSON, or not what the update {@code accepts}. Empty when fine. */
    static List<String> payloadProblems(UpdateDefinition handler, String payload) {
        var json = payload == null || payload.isBlank() ? "null" : payload;
        try {
            CANONICAL.readTree(json);
        } catch (Exception e) {
            return List.of("it is not JSON");
        }
        if (handler.accepts() == null) {
            return List.of();
        }
        return AgentSchemas.validate(handler.accepts(), json);
    }

    /**
     * The request in the canonical form its fingerprint is computed over: the update's name and its payload
     * with object members sorted, so the same payload written differently is the same request. The
     * idempotency key is not in it (it is the lookup), and neither is the wait. Fingerprinted with
     * {@code VariableEncryption.fingerprint} — keyed when encryption is configured.
     */
    static String canonicalOf(String updateName, String payload) {
        String canonical;
        try {
            canonical = payload == null || payload.isBlank() ? "null"
                    : CANONICAL.writeValueAsString(CANONICAL.readValue(payload, Object.class));
        } catch (Exception e) {
            canonical = payload;
        }
        return updateName + "\n" + canonical;
    }

    /** The plain digest (no keys configured), for callers that have no encryption at hand. */
    static String fingerprint(String updateName, String payload) {
        return io.mateu.workflow.crypto.Fingerprints.of(canonicalOf(updateName, payload), null);
    }

    /**
     * The payload as recorded: its protected fields (see {@code UpdateEvaluator#protectedFields}) sealed —
     * masked without encryption. An update the definition does not declare has no rules to go by: with
     * anything sensitive in the process, its payload is not kept at all.
     */
    static String recorded(UpdateDefinition handler, String payload, io.mateu.workflow.crypto.SensitiveNames sensitive,
                           io.mateu.workflow.crypto.VariableEncryption encryption) {
        if (handler == null) {
            return sensitive.isEmpty() || payload == null ? payload : "\"" + UpdateEvaluator.MASK + "\"";
        }
        return UpdateEvaluator.seal(handler, payload, sensitive.isEmpty() ? null : sensitive, encryption);
    }

    /** The payload as carried to the process's owner: sealed like the record when there are keys, as it came otherwise. */
    private String carried(UpdateDefinition handler, String payload, io.mateu.workflow.crypto.SensitiveNames sensitive) {
        return encryption.enabled() ? recorded(handler, payload, sensitive, encryption) : payload;
    }

    /**
     * An update as its caller is answered — the POST that sent it, or a retry with its key: the result
     * opened when it was sealed (an update allowed to read sensitive variables), the payload shown masked.
     */
    public ProcessUpdate forCaller(ProcessUpdate update) {
        if (update == null) {
            return null;
        }
        var result = update.result();
        if (io.mateu.workflow.crypto.SensitivePayloads.containsSealed(result)) {
            try {
                result = UpdateEvaluator.open(result, encryption);
            } catch (RuntimeException e) {
                result = UpdateEvaluator.shown(result);
            }
        }
        return withShown(update, UpdateEvaluator.shown(update.payload()), result);
    }

    /**
     * An update as every other door shows it (GET, listings, MCP, the console): sealed payload fields and a
     * sealed result masked, and the result's members named like a sensitive variable of the process masked.
     */
    public ProcessUpdate shownForProcess(ProcessUpdate update) {
        if (update == null) {
            return null;
        }
        var sensitive = processRepository.findById(update.processId())
                .map(io.mateu.workflow.application.services.SensitiveVariableMasking::namesOf)
                .orElse(io.mateu.workflow.crypto.SensitiveNames.NONE);
        var shown = shown(update);
        return withShown(shown, shown.payload(), maskMembers(shown.result(), sensitive));
    }

    /** A JSON object with the members named like a sensitive variable masked; anything else as it is. */
    public static String maskMembers(String json, io.mateu.workflow.crypto.SensitiveNames sensitive) {
        if (json == null || sensitive.isEmpty()) {
            return json;
        }
        try {
            var tree = CANONICAL.readTree(json);
            if (!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
                return json;
            }
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            var changed = false;
            for (var field : fields) {
                if (sensitive.test(field)) {
                    object.put(field, UpdateEvaluator.MASK);
                    changed = true;
                }
            }
            return changed ? new ObjectMapper().writeValueAsString(object) : json;
        } catch (Exception e) {
            return json;
        }
    }

    /** {@link #shownForProcess} without the process: what is sealed is masked. */
    public static ProcessUpdate shown(ProcessUpdate update) {
        return withShown(update, UpdateEvaluator.shown(update.payload()), UpdateEvaluator.shown(update.result()));
    }

    private static ProcessUpdate withShown(ProcessUpdate update, String payload, String result) {
        return new ProcessUpdate(update.id(), update.processId(), update.workflowDefinitionId(), update.name(),
                update.idempotencyKey(), update.requestHash(), payload, update.status(), result, update.error(),
                update.requestedBy(), update.withStart(), update.requestedAt(), update.completedAt(), update.expiresAt());
    }

    private static String subject(AuthorizationContext caller) {
        return caller == null ? null : caller.subject();
    }
}
