package io.mateu.workflow.test;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.infra.in.scheduler.InMemoryCronStartScheduler;
import io.mateu.workflow.infra.in.scheduler.InMemoryTimeoutScheduler;
import io.mateu.workflow.infra.in.scheduler.SchedulerPass;
import io.mateu.workflow.time.EngineClock;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Drives the engine from a test: start processes, move the clock, deliver messages and events,
 * answer tasks, and read back what happened. Inject it into an {@link EventConductorTest} (as a test
 * method parameter, or {@code @Autowired}).
 *
 * <p>Everything is synchronous. The engine runs in memory, its workers are the kit's mocks, and its
 * schedulers do not tick on their own: a call returns once the engine has done everything it can do
 * at the current (test) time. Time moves only through {@link #advanceTime(Duration)}, which jumps
 * the clock from one due moment to the next — every TIMER, timeout, retry backoff and cron
 * occurrence in between fires at its own moment — so a test of a three-day wait takes milliseconds.
 */
public final class WorkflowTestRuntime {

    /** Passes run at one moment before giving up on reaching idle (a loop that never settles). */
    static final int MAX_PASSES = 10_000;
    /** Due moments visited by one {@code advanceTime} before giving up (a schedule firing every second for years). */
    static final int MAX_JUMPS = 1_000_000;

    private final TestClock clock;
    private final StubbingTaskExecutor executor;
    private final PublishedEventRecorder published;
    private final ApplicationContext context;

    WorkflowTestRuntime(TestClock clock, StubbingTaskExecutor executor, PublishedEventRecorder published,
                        ApplicationContext context) {
        this.clock = clock;
        this.executor = executor;
        this.published = published;
        this.context = context;
    }

    // ---------------------------------------------------------------- lifecycle (the extension's)

    /** Called before each test: installs the clock, forgets the previous test's mocks, catches up. */
    void beginTest() {
        EngineClock.set(clock);
        var existing = repository(ProcessRepository.class).findAll().stream().map(Process::id)
                .collect(Collectors.toCollection(HashSet::new));
        executor.reset(existing);
        published.clear();
        runUntilIdle();
    }

    /** Called after each test: the engine clock goes back to the system clock. */
    void endTest() {
        EngineClock.reset();
    }

    // ---------------------------------------------------------------- definitions

    /**
     * Imports workflow definitions from files or directories (JSON, YAML, {@code .ec}), on top of
     * those already on the classpath under {@code workflows/}. A {@code classpath:} location is read
     * from the classpath. Fails the test when a definition does not import.
     */
    public void deploy(String... locations) {
        for (var location : locations) {
            deploy(resolve(location));
        }
    }

    /** {@link #deploy(String...)} for a path. */
    public void deploy(Path location) {
        Path directory = location;
        try {
            if (!Files.isDirectory(location)) {
                if (!Files.isRegularFile(location)) {
                    throw new IllegalArgumentException("No workflow definition at " + location.toAbsolutePath());
                }
                directory = Files.createTempDirectory("ec-workflow-test");
                Files.copy(location, directory.resolve(location.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var result = context.getBean(ImportWorkflowDefinitionsFromDirectoryUseCase.class)
                .handle(List.of(directory.toString()));
        if (!result.errors().isEmpty()) {
            throw new AssertionError("Workflow definitions from " + location + " did not import:\n  "
                    + String.join("\n  ", result.errors()));
        }
    }

    private Path resolve(String location) {
        if (location.startsWith("classpath:")) {
            var resource = context.getResource(location);
            try {
                return Path.of(resource.getURI());
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("Cannot read " + location + " as a file: " + e.getMessage(), e);
            }
        }
        return Path.of(location);
    }

    // ---------------------------------------------------------------- processes

    /** Starts a process of {@code definitionId} with a generated business key and no variables. */
    public TestProcess start(String definitionId) {
        return start(definitionId, null, Map.of());
    }

    /** Starts a process of {@code definitionId} with a generated business key. */
    public TestProcess start(String definitionId, Map<String, ?> variables) {
        return start(definitionId, null, variables);
    }

    /**
     * Starts a process and runs it as far as it goes now — through every step whose task answers at
     * once — then returns it. Business keys are unique per engine (a second start with the same key
     * is the same process), so give each test its own, or pass null for a generated one. Values that
     * are not text are stored as JSON.
     */
    public TestProcess start(String definitionId, String businessKey, Map<String, ?> variables) {
        var key = businessKey != null ? businessKey : definitionId + "-" + UUID.randomUUID();
        upstream().handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested(definitionId, key, Values.dtoVariables(variables))));
        return process(key);
    }

    /** The process with this business key — one a test started, or one the engine did (cron, trigger, child). */
    public TestProcess process(String businessKey) {
        var process = repository(ProcessRepository.class).findByBusinessKey(businessKey)
                .orElseThrow(() -> new AssertionError("No process with business key '" + businessKey + "'"));
        return handle(process);
    }

    /** Every process of {@code definitionId}, oldest first — what a cron schedule or a trigger started. */
    public List<TestProcess> processes(String definitionId) {
        return repository(ProcessRepository.class).findAll().stream()
                .filter(process -> Objects.equals(definitionId, process.getWorkflowDefinitionId()))
                .sorted(Comparator.comparing(Process::getCreated, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::handle)
                .toList();
    }

    /** Cancels a process, as an operator does from the UI or the API: nothing more of it runs. */
    public void cancel(TestProcess process) {
        context.getBean(CancelProcessUseCase.class).handle(new CancelProcessCommand(process.id()));
        runUntilIdle();
    }

    // ---------------------------------------------------------------- time

    /** The engine's current time. */
    public LocalDateTime now() {
        return clock.now();
    }

    /** The clock the engine reads. */
    public TestClock clock() {
        return clock;
    }

    /**
     * Moves the clock forward by {@code duration}, stopping at every moment something falls due on
     * the way — a TIMER, a step timeout, a retry backoff, a cron occurrence — and letting the engine
     * act on it there before going on. Returns once the clock reads the target and nothing more is
     * due. A TIMER fires at its due moment; a timeout, which expires strictly after its limit, one
     * millisecond later — so a one-hour timeout has fired after {@code advanceTime(Duration.ofMinutes(61))}
     * but not yet at exactly sixty minutes.
     */
    public void advanceTime(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Time only moves forward (asked to move " + duration + ")");
        }
        advanceTimeTo(clock.instant().plus(duration));
    }

    /** {@link #advanceTime(Duration)} to an absolute moment, in the engine's zone. */
    public void advanceTimeTo(LocalDateTime target) {
        advanceTimeTo(target.atZone(clock.getZone()).toInstant());
    }

    private void advanceTimeTo(Instant target) {
        runUntilIdle();
        for (var jumps = 0; ; jumps++) {
            if (jumps > MAX_JUMPS) {
                throw new IllegalStateException("advanceTime visited more than " + MAX_JUMPS
                        + " due moments; is a schedule firing every second?");
            }
            var next = nextDueMoment(target);
            if (next == null) {
                break;
            }
            clock.advanceTo(next);
            runUntilIdle();
        }
        clock.advanceTo(target);
        runUntilIdle();
    }

    /** The earliest moment after now and not after {@code target} at which something is due. */
    private Instant nextDueMoment(Instant target) {
        var now = clock.instant();
        Instant next = null;
        var horizon = LocalDateTime.ofInstant(target, clock.getZone());
        for (var execution : repository(StepExecutionRepository.class).findDue(horizon)) {
            var deadline = execution.getDeadlineAt();
            if (deadline == null) {
                continue;
            }
            var moment = deadline.atZone(clock.getZone()).toInstant();
            if (moment.equals(now)) {
                // Still due at its own moment after the engine settled: a timeout, which expires
                // strictly after its limit. The next millisecond is when a scan would first see it.
                moment = now.plusMillis(1);
            }
            if (moment.isAfter(now) && !moment.isAfter(target) && (next == null || moment.isBefore(next))) {
                next = moment;
            }
        }
        // Whatever else a pass knows it has due — a debounce window closing, a throttle window opening.
        for (var pass : schedulerPasses()) {
            var due = pass.nextDueAt().orElse(null);
            if (due != null && due.isAfter(now) && !due.isAfter(target) && (next == null || due.isBefore(next))) {
                next = due;
            }
        }
        var cron = context.getBeanProvider(InMemoryCronStartScheduler.class).getIfAvailable();
        if (cron != null) {
            var occurrence = cron.nextOccurrence().orElse(null);
            if (occurrence != null && occurrence.isAfter(now) && !occurrence.isAfter(target)
                    && (next == null || occurrence.isBefore(next))) {
                next = occurrence;
            }
        }
        return next;
    }

    /**
     * Runs every scheduler pass at the current time — deadline scan (timeouts, TIMERs, retries), cron
     * starts, retention purges — again and again until a pass changes nothing. Most calls do not need
     * it ({@code start}, {@code sendMessage} … already run the engine as far as it goes); it is for
     * work that is due now without the clock moving, such as a retry with no backoff.
     */
    public void runUntilIdle() {
        var passes = schedulerPasses();
        for (var round = 0; round < MAX_PASSES; round++) {
            var before = dueFingerprint();
            var others = 0;
            for (var pass : passes) {
                var acted = pass.runOnce();
                if (!(pass instanceof InMemoryTimeoutScheduler)) {
                    others += acted;
                }
            }
            if (others == 0 && before.equals(dueFingerprint())) {
                return;
            }
        }
        throw new IllegalStateException("The engine did not settle after " + MAX_PASSES
                + " scheduler passes at " + now() + "; is a step re-scheduling itself with no delay?");
    }

    private List<SchedulerPass> schedulerPasses() {
        var passes = new ArrayList<>(context.getBeansOfType(SchedulerPass.class).values());
        // The deadline scan first: what it completes may be what a purge or a cron start looks at.
        passes.sort(Comparator.comparing(pass -> pass instanceof InMemoryTimeoutScheduler ? 0 : 1));
        return passes;
    }

    private List<String> dueFingerprint() {
        return repository(StepExecutionRepository.class).findDue(now()).stream()
                .map(e -> e.id() + '|' + e.getStatus() + '|' + e.getAttemptCount() + '|' + e.getDeadlineAt())
                .sorted()
                .toList();
    }

    // ---------------------------------------------------------------- messages and events

    /** Delivers a message, correlated by {@code correlationKey} (the business key unless the step says otherwise). */
    public void sendMessage(String messageName, String correlationKey) {
        sendMessage(messageName, correlationKey, Map.of());
    }

    /** Delivers a message with variables that are merged into the process it resumes. */
    public void sendMessage(String messageName, String correlationKey, Map<String, ?> variables) {
        upstream().handle(new ProcessUpstreamEventCommand(
                new MessageReceived(messageName, correlationKey, Values.dtoVariables(variables))));
    }

    /**
     * Delivers a domain event (CloudEvents {@code type}) on a destination, as if it had arrived on its
     * topic: it starts the processes whose {@code triggers} match and resumes the steps whose
     * {@code onEvent} matches. {@code data} is JSON text, or an object written as JSON.
     */
    public InboundEventService.Result publishEvent(String destination, String type, Object data) {
        var json = data == null ? null : Values.text(data);
        return context.getBean(InboundEventService.class).receive(new InboundEvent(destination,
                UUID.randomUUID().toString(), "workflow-test", type, null, null, null, "application/json", json, Map.of()));
    }

    /** What the PUBLISH_EVENT steps of {@code process} published, in order. */
    public List<ExternalEventRequested> publishedEvents(TestProcess process) {
        return published.forProcess(process.id());
    }

    // ---------------------------------------------------------------- updates

    /**
     * Sends a named update (declared in the definition's {@code updates}) to a running process and applies
     * it at once, as the owner of the process would — then lets the engine run as far as the update lets it.
     * {@code payload} is a map, a list or a value (written as JSON), or JSON text. Returns how it ended; a
     * refusal is an outcome, not an exception.
     */
    public UpdateOutcome update(TestProcess process, String updateName, Object payload) {
        return update(process, updateName, payload, null);
    }

    /** {@link #update(TestProcess, String, Object)} with an idempotency key: the same key again is the same update. */
    public UpdateOutcome update(TestProcess process, String updateName, Object payload, String idempotencyKey) {
        var service = context.getBean(io.mateu.workflow.application.updates.ProcessUpdateService.class);
        try {
            var submitted = service.submitInline(new io.mateu.workflow.application.updates.ProcessUpdateService.UpdateRequest(
                    process.id(), null, updateName, payloadJson(payload), idempotencyKey, Duration.ZERO, null));
            runUntilIdle();
            var latest = repository(io.mateu.workflow.application.out.ProcessUpdateRepository.class)
                    .findById(submitted.update().id()).orElse(submitted.update());
            return UpdateOutcome.of(latest, process, false);
        } catch (io.mateu.workflow.application.updates.ProcessUpdateRejectedException e) {
            return UpdateOutcome.of(e, process);
        }
    }

    /**
     * An update-with-start: the update goes to the process with {@code businessKey}, or — when there is
     * none — a process of {@code definitionId} is started with {@code variables} and the update as its
     * first act. {@link UpdateOutcome#started()} says which; {@link UpdateOutcome#process()} is the process.
     */
    public UpdateOutcome updateWithStart(String definitionId, String businessKey, Map<String, ?> variables,
                                         String updateName, Object payload) {
        var service = context.getBean(io.mateu.workflow.application.updates.ProcessUpdateService.class);
        var initial = new java.util.LinkedHashMap<String, String>();
        if (variables != null) {
            variables.forEach((name, value) -> initial.put(name, Values.text(value)));
        }
        try {
            var outcome = service.updateWithStartInline(new io.mateu.workflow.application.updates.ProcessUpdateService.StartRequest(
                    definitionId, businessKey, initial, updateName, payloadJson(payload), null, Duration.ZERO, null));
            runUntilIdle();
            var latest = repository(io.mateu.workflow.application.out.ProcessUpdateRepository.class)
                    .findById(outcome.update().id()).orElse(outcome.update());
            return UpdateOutcome.of(latest, process(businessKey), outcome.started());
        } catch (io.mateu.workflow.application.updates.ProcessUpdateRejectedException e) {
            var existing = repository(ProcessRepository.class).findByBusinessKey(businessKey).map(this::handle).orElse(null);
            return UpdateOutcome.of(e, existing);
        }
    }

    List<io.mateu.workflow.application.updates.ProcessUpdate> updatesOf(String processId) {
        return repository(io.mateu.workflow.application.out.ProcessUpdateRepository.class).findByProcessId(processId);
    }

    /** A payload as JSON text: a string that is JSON stays itself, any other string becomes a JSON string. */
    private static String payloadJson(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String text) {
            try {
                Values.JSON.readTree(text);
                return text;
            } catch (IOException notJson) {
                try {
                    return Values.JSON.writeValueAsString(text);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return Values.text(payload);
    }

    // ---------------------------------------------------------------- tasks

    /** Mocks a step's task by step id — the most specific mock. */
    public TaskStub whenStep(String stepId) {
        return executor.stepStub(stepId);
    }

    /**
     * Mocks a task by its task id ({@code charge-card}, any version) or by the topic of the steps that
     * dispatch it — the way a worker subscribes. A {@link #whenStep} mock wins over it.
     */
    public TaskStub whenTask(String taskIdOrTopic) {
        return executor.taskStub(taskIdOrTopic);
    }

    /** Stubs the response of an HTTP_CALL step. Without a stub an HTTP_CALL fails: a test never reaches the network. */
    public HttpStub whenHttpCall(String stepId) {
        return executor.httpStub(stepId);
    }

    /** Stubs the answer of an LLM_CALL step. Without a stub an LLM_CALL fails: a test never calls a model. */
    public LlmStub whenLlmCall(String stepId) {
        return executor.llmStub(stepId);
    }

    /**
     * Scripts the model of an AGENT_CALL step: its turns ask for tool calls and finally answer. Without a
     * script the agent's first turn fails: a test never calls a model. Its tools are answered by the
     * ordinary stubs — see {@link AgentStub}.
     */
    public AgentStub whenAgent(String stepId) {
        return executor.agentStub(stepId);
    }

    /** Completes a task that is waiting for its worker (not mocked, or mocked with {@code thenTimeout}). */
    public void completeTask(TestProcess process, String stepId) {
        completeTask(process, stepId, Map.of());
    }

    /** {@link #completeTask(TestProcess, String)}, writing these variables onto the process. */
    public void completeTask(TestProcess process, String stepId, Map<String, ?> variables) {
        executor.reply(parked(process, stepId), TaskOutcome.complete(variables));
    }

    /** Fails a task that is waiting for its worker, as its worker would. */
    public void failTask(TestProcess process, String stepId, String code, boolean retryable) {
        executor.reply(parked(process, stepId), TaskOutcome.fail(code, retryable));
    }

    /** Completes a USER_TASK with what the person submitted. */
    public void completeUserTask(TestProcess process, String stepId, Map<String, ?> submitted) {
        var waiting = parked(process, stepId);
        if (!"complete-form".equals(waiting.taskId())) {
            executor.reply(waiting, TaskOutcome.noReply());
            throw new AssertionError("Step '" + stepId + "' of " + process + " is not a USER_TASK (its task is '"
                    + waiting.taskId() + "'); use completeTask");
        }
        executor.reply(waiting, TaskOutcome.complete(submitted));
    }

    private io.mateu.workflow.dtos.events.integration.TaskExecutionRequested parked(TestProcess process, String stepId) {
        var waiting = executor.unpark(process.id(), stepId);
        if (waiting == null) {
            throw new AssertionError("Step '" + stepId + "' of " + process + " has no task waiting for an answer"
                    + " (waiting: " + executor.parked().stream()
                    .filter(request -> process.id().equals(request.processId()))
                    .map(request -> request.stepId()).toList() + ")");
        }
        return waiting;
    }

    /** Every task dispatch the kit intercepted during this test, in order. */
    public List<TaskInvocation> invocations() {
        return executor.invocations();
    }

    /** The dispatches of one step (in any process) during this test. */
    public List<TaskInvocation> invocationsOf(String stepId) {
        return executor.invocations().stream().filter(invocation -> stepId.equals(invocation.stepId())).toList();
    }

    // ---------------------------------------------------------------- engine access

    Process processState(String id) {
        return repository(ProcessRepository.class).findById(id)
                .orElseThrow(() -> new AssertionError("Process " + id + " no longer exists (purged?)"));
    }

    List<StepExecution> stepExecutions(String processId) {
        return repository(StepExecutionRepository.class).findByProcessId(processId);
    }

    private TestProcess handle(Process process) {
        return new TestProcess(this, process.id(), process.getBusinessKey(), process.getWorkflowDefinitionId());
    }

    private ProcessUpstreamEventUseCase upstream() {
        return context.getBean(ProcessUpstreamEventUseCase.class);
    }

    private <T> T repository(Class<T> type) {
        return context.getBean(type);
    }
}
