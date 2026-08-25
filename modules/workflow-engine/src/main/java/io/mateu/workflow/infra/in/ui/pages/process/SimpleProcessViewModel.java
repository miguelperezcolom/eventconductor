package io.mateu.workflow.infra.in.ui.pages.process;


import io.mateu.workflow.dtos.MessageType;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.annotations.Tab;
import io.mateu.uidl.data.*;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.VisibilitySupplier;
import io.mateu.uidl.data.Element;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ResourceRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessCommand;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessCommand;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Errors;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Message;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Messages;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resource;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resources;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Steps;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.With;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;
import static io.mateu.uidl.Humanizer.toUpperCaseFirst;
import static io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter.mapProcessStatus;

@RequiredArgsConstructor
@Getter
@ReadOnly
// Use the whole screen width (uncapped): the diagram tab is a wide monitoring canvas.
@PageWidth(PageWidthStyle.FULL_WIDTH)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
public class SimpleProcessViewModel implements TriggersSupplier, VisibilitySupplier {

    /** Custom element + ESM bundle that render the workflow as a read-only ELK graph. */
    private static final String GRAPH_TAG = "eventconductor-workflow-graph";
    private static final String GRAPH_MODULE = "/eventconductor/workflow-graph.js";

    final io.mateu.workflow.application.services.CommandDispatcher commandDispatcher;
    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final LogMessageRepository logMessageRepository;
    final ResourceRepository resourceRepository;
    final CancelProcessUseCase cancelProcessUseCase;
    final RetryProcessUseCase retryProcessUseCase;
    final PauseProcessUseCase pauseProcessUseCase;
    final ResumeProcessUseCase resumeProcessUseCase;


    String id;

    String name;

    Status status;

    /** Raw domain status, kept for the pause/resume toolbar visibility rules (the badge shows {@link #status}). */
    @Hidden
    ProcessStatus processStatus;

    @ReadOnly
    String returnTo;

    // Explicit tab names: since mateu 379440d83 consecutive @Tab annotations with the SAME
    // value (and bare @Tab means value "") are merged into one tab, which stacked these four
    // lists vertically. Distinct values keep one tab per list.
    @Tab("Diagram")
    @Label("")
    Element diagram;

    /**
     * What the diagram draws, as a <em>value</em> rather than as component metadata.
     *
     * <p>An {@link Element}'s attributes belong to the component tree, and a {@code State} update
     * deliberately does not resend that tree — it carries values. So a diagram whose attributes were
     * written as literals was frozen as of the render that built it: an operator watching a running
     * process saw the picture the tab opened with, for the life of the tab, while the process ran to
     * completion behind it. Nothing looked broken, which is why it went unnoticed for months; only
     * the colours were a lie.
     *
     * <p>These two fields are the fix, and they are plain {@code String}s on purpose: that is what
     * makes them data. The element's attributes now say {@code ${state.processGraph}} and
     * {@code ${state.processGraphOverlay}} — where to read it — and mateu interpolates them against
     * the state on every render, applying the result with {@code setAttribute} on the element that
     * is already there. The custom element turns that into a property change and repaints, keeping
     * the zoom, the selection and the ELK layout it computed; it is not rebuilt.
     *
     * <p>Hidden because they are not for reading: this is a JSON payload for the component, and the
     * tab shows the diagram, not its source.
     */
    @Hidden
    String processGraph;

    /** Each step's live state, by step id. See {@link #processGraph}. */
    @Hidden
    String processGraphOverlay;

    @Tab("Steps")
    @Label("")
    List<Step> steps;

    @Tab("Messages")
    @Label("")
    List<Message> messages;

    @Tab("Errors")
    @Label("")
    List<Error> errors;

    @Tab("Resources")
    @Label("")
    List<Resource> resources;

    @Tab
    @Label("")
    List<Variable> variables;

    public Object load(String id, HttpRequest httpRequest) {
        this.id = id;
        Process process = processRepository.findById(id).orElse(processRepository.findByBusinessKey(id).orElse(null));
        this.name = process.getName();
        this.processStatus = process.getStatus();
        this.status = mapProcessStatus(process.getStatus(), process.getCompletionPercentage());
        var stepExecutions = stepExecutionRepository.findByProcess(process);
        this.steps = stepExecutions.stream()
                .map(se -> new Step(id, se.id(), se.getStepId(), mapStepStatus(se.getStatus().name())))
                .toList();
        var logs = logMessageRepository.findByProcessId(id);
        this.diagram = buildDiagram(process, stepExecutions, logs);
        this.messages = logs.stream()
                .filter(msg -> !MessageType.isError(msg.getMessageType()))
                .sorted(Comparator.comparing(LogMessage::getTimestamp).reversed())
                .limit(10)
                .map(msg -> new Message(id, msg.id(), msg.getTimestamp(), msg.getMessage()))
                .toList();
        this.errors = logs.stream()
                .filter(msg -> MessageType.isError(msg.getMessageType()))
                .sorted(Comparator.comparing(LogMessage::getTimestamp).reversed())
                .limit(10)
                .map(msg -> new Error(id, msg.id(), msg.getTimestamp(), msg.getMessage()))
                .toList();
        this.resources = resourceRepository.findByProcessId(id).stream()
                .map(r -> new Resource(id, r.id(), r.getName(), r.getUrl()))
                .toList();
        this.variables = process.getVariables().stream().map(variable -> new Variable(variable.name(), variable.value())).toList();
        this.returnTo = httpRequest.getParameterValue("returnTo");

        if (ProcessStatus.COMPLETED.equals(process.getStatus())) {
            var returnTo = (String) httpRequest.runActionRq().componentState().get("returnTo");
            if (returnTo == null) {
                if (httpRequest.runActionRq().route().contains("returnTo")) {
                    returnTo = httpRequest.runActionRq().route().substring(httpRequest.runActionRq().route().indexOf("returnTo=") + "returnTo=".length());
                }
            }
            if (returnTo != null) {
                return URI.create(returnTo);
            }
        }
        return this;
    }

    /**
     * The read-only graph for this process, overlaid with each step's live state and an "active"
     * highlight on the running step(s) — so the diagram shows where the process currently is.
     */
    Element buildDiagram(Process process, List<StepExecution> stepExecutions, List<LogMessage> logs) {
        var def = workflowDefinitionRepository.findById(process.getWorkflowDefinitionId()).orElse(null);
        if (def == null) return null;
        // Collapse retries: keep the most telling execution per step (running/error over completed,
        // and the latest attempt when equally telling) so the overlay reads the current situation.
        var byStep = new HashMap<String, StepExecution>();
        for (var se : stepExecutions) {
            byStep.merge(se.getStepId(), se, (a, b) -> {
                int ra = statusRank(a.getStatus()), rb = statusRank(b.getStatus());
                if (rb != ra) return rb > ra ? b : a;
                return b.getAttemptCount() >= a.getAttemptCount() ? b : a;
            });
        }
        var latestErrorByExec = latestErrorByStepExecution(logs);
        var order = executionOrder(byStep.values());
        var overlay = new HashMap<String, Object>();
        byStep.forEach((stepId, se) -> {
            var entry = overlayEntry(se, latestErrorByExec.get(se.id()));
            var position = order.get(stepId);
            if (position != null) entry.put("order", position);
            overlay.put(stepId, entry);
        });
        var attrs = new HashMap<String, String>();
        attrs.put("import", GRAPH_MODULE);
        // The diagram must show the process's ACTUAL step set, not just the definition: steps a
        // DYNAMIC step injected at runtime are not in the definition, so a value built from it alone
        // would render a graph the running process never had. Feed the union — declared steps plus
        // the injected ones (each execution carries its own frozen stepJson) — so injected nodes
        // render with their real preconditions. The plain definition-editor view is untouched: it
        // renders the definition directly and never comes through here.
        this.processGraph = toJson(withInjectedSteps(def, stepExecutions));
        this.processGraphOverlay = overlay.isEmpty() ? "" : toJson(overlay);
        // Where to read it, not the thing itself — see the fields' own note. The topology travels
        // this way too and not only the overlay: a DYNAMIC step injects nodes while the process
        // runs, so the graph's shape changes under a page that is already open.
        attrs.put("value", "${state.processGraph}");
        attrs.put("readonly", "true");
        if (!overlay.isEmpty()) attrs.put("overlay", "${state.processGraphOverlay}");
        // Give the graph a tall, viewport-sized box. Inside a tab the host has no height context and
        // falls back to its ~230px min-height, which is far too short for monitoring a live process.
        return Element.builder()
                .name(GRAPH_TAG)
                .attributes(attrs)
                .content("")
                .style("display: block; height: 68vh; min-height: 460px;")
                .build();
    }

    /**
     * The definition augmented with the steps a DYNAMIC step injected into this process at runtime.
     *
     * <p>Injected steps are not in the definition — each lives only as a step execution carrying its
     * own frozen {@code stepJson} and marked with {@code injectedByStepExecutionId}. This appends
     * those (deserialised back to {@link io.mateu.workflow.domain.aggregates.Step}s) after the
     * declared steps, so the graph value is the process's real topology. When nothing was injected
     * the definition is returned untouched, so the common case pays nothing and behaves exactly as
     * before.
     */
    static io.mateu.workflow.domain.aggregates.WorkflowDefinition withInjectedSteps(
            io.mateu.workflow.domain.aggregates.WorkflowDefinition def, List<StepExecution> stepExecutions) {
        var declaredIds = def.steps().stream()
                .map(io.mateu.workflow.domain.aggregates.Step::id)
                .collect(java.util.stream.Collectors.toSet());
        // One entry per injected step id (retries share it), in execution order, skipping any id the
        // definition already carries so an injected step never doubles a declared one.
        var injectedById = new java.util.LinkedHashMap<String, io.mateu.workflow.domain.aggregates.Step>();
        for (var se : stepExecutions) {
            if (se.getInjectedByStepExecutionId() == null) continue;
            var step = safeStep(se.getStepJson());
            if (step == null || step.id() == null) continue;
            if (declaredIds.contains(step.id()) || injectedById.containsKey(step.id())) continue;
            injectedById.put(step.id(), step);
        }
        if (injectedById.isEmpty()) return def;
        var allSteps = new ArrayList<>(def.steps());
        allSteps.addAll(injectedById.values());
        return def.withSteps(allSteps);
    }

    /** Overlay state token understood by the graph component. */
    static String overlayState(StepExecutionStatus status) {
        return switch (status) {
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case ERROR, TIMEOUT -> "ERROR";
            case CANCELLED -> "CANCELLED";
            // A step waiting out its retry backoff reads as pending work on the graph, not as an
            // error — it failed but is going to run again, and the overlay reason spells that out.
            case CREATED, PENDING, AWAITING_RETRY -> "PENDING";
        };
    }

    /** How "telling" a status is when several executions exist for one step (retries). */
    static int statusRank(StepExecutionStatus status) {
        return switch (status) {
            case ERROR, TIMEOUT -> 5;
            case RUNNING -> 4;
            // A pending retry outranks a plain pending: it is the more telling of the two when a
            // step has several executions, because it says the last attempt failed.
            case AWAITING_RETRY -> 4;
            case PENDING -> 3;
            case CREATED -> 2;
            case COMPLETED -> 1;
            case CANCELLED -> 0;
        };
    }

    /**
     * The overlay entry the graph hover reads: the step's state plus the consolidated "why it is
     * here" and the detail an operator needs to answer it without opening the code — the parked
     * reason, the last error, retry count, what it awaits, deadlines and its variable snapshot.
     */
    static Map<String, Object> overlayEntry(StepExecution se, String error) {
        var step = safeStep(se.getStepJson());
        var status = se.getStatus();
        var entry = new HashMap<String, Object>();
        entry.put("state", overlayState(status));
        entry.put("reason", reasonFor(se, step, error));
        // A step a DYNAMIC step added at runtime, not one the definition declared. The graph badges
        // these so an operator can tell what the running process grew from what its author wrote;
        // the injector id is carried too, for tooling that wants to trace it back.
        if (se.getInjectedByStepExecutionId() != null) {
            entry.put("injected", true);
            entry.put("injectedBy", se.getInjectedByStepExecutionId());
        }
        if (status == StepExecutionStatus.RUNNING) entry.put("active", true);
        if (error != null) entry.put("error", error);
        if (se.getAttemptCount() > 0) entry.put("attempt", se.getAttemptCount());
        if (step != null && step.retries() > 0) entry.put("maxRetries", step.retries());
        if (se.getAwaitingMessageName() != null) entry.put("awaitingMessage", se.getAwaitingMessageName());
        if (se.getAwaitingCorrelationKey() != null) entry.put("correlationKey", se.getAwaitingCorrelationKey());
        if (se.getDeadlineAt() != null) entry.put("deadlineAt", se.getDeadlineAt().toString());
        if (se.getStartedAt() != null) entry.put("startedAt", se.getStartedAt().toString());
        if (se.getWorkerId() != null) entry.put("worker", se.getWorkerId());
        var vars = se.getVariables();
        if (vars != null && !vars.isEmpty()) {
            entry.put("variables", vars.stream()
                    .limit(12)
                    .map(v -> Map.of("name", v.name() == null ? "" : v.name(),
                            "value", v.value() == null ? "" : v.value()))
                    .toList());
        }
        return entry;
    }

    /**
     * What ran first, second, third — by step id, for the numbers the diagram puts on its nodes.
     *
     * <p>The graph draws the shape of a workflow, and the shape does not say what order a
     * particular process actually took: two branches drawn side by side ran in some order, a loop
     * drawn as one node ran several times, and a step that was skipped is drawn exactly where it
     * would have been. A tick answers "did this run"; the number answers "when", which is the
     * question an operator reading a finished process is usually asking.
     *
     * <p>Ordered by {@code startedAt}, since that is when a step took its turn — not by when it
     * finished, which would number a slow first step after the quick one that followed it. A step
     * that has no {@code startedAt} is ordered by {@code finishedAt} instead: not every step gets
     * one, since it is stamped where a task is dispatched to a worker and an END step is never
     * dispatched anywhere. Leaving those unnumbered was the first attempt and it was worse than no
     * numbers at all — the browser test caught an END wearing a tick and no number, which reads as
     * a step that never ran.
     *
     * <p>Steps with neither timestamp are left out and get no number, which is the honest reading:
     * an unnumbered node is one that has not had its turn.
     *
     * <p>Ties are broken by step id so that two steps starting in the same instant — which
     * parallel branches routinely do — are numbered the same way on every poll. An arbitrary but
     * stable order beats a number that changes under the reader every two seconds.
     */
    static Map<String, Integer> executionOrder(Collection<StepExecution> executions) {
        var started = executions.stream()
                .filter(se -> whenItRan(se) != null)
                .sorted(Comparator.comparing(SimpleProcessViewModel::whenItRan)
                        .thenComparing(StepExecution::getStepId))
                .toList();
        var order = new HashMap<String, Integer>();
        for (var i = 0; i < started.size(); i++) {
            order.put(started.get(i).getStepId(), i + 1);
        }
        return order;
    }

    /** When a step took its turn: when it started, or — for the steps nothing dispatches — when it
     * finished. Null for a step that has done neither. */
    private static LocalDateTime whenItRan(StepExecution se) {
        return se.getStartedAt() != null ? se.getStartedAt() : se.getFinishedAt();
    }

    /** Latest error message per step execution id, so a failed step can show its own cause. */
    static Map<String, String> latestErrorByStepExecution(List<LogMessage> logs) {
        var latest = new HashMap<String, String>();
        var when = new HashMap<String, LocalDateTime>();
        for (var lm : logs) {
            if (!MessageType.isError(lm.getMessageType())) continue;
            var eid = lm.getStepExecutionId();
            if (eid == null) continue;
            var ts = lm.getTimestamp();
            if (!when.containsKey(eid) || (ts != null && ts.isAfter(when.get(eid)))) {
                when.put(eid, ts);
                latest.put(eid, lm.getMessage());
            }
        }
        return latest;
    }

    /** Human-legible answer to "why is this step here?", read straight from the DSL vocabulary. */
    static String reasonFor(StepExecution se, io.mateu.workflow.domain.aggregates.Step step, String error) {
        var type = step != null ? step.type() : null;
        return switch (se.getStatus()) {
            case RUNNING -> "Running" + (se.getWorkerId() != null ? " on worker " + se.getWorkerId() : "");
            case CREATED -> se.getAttemptCount() > 0 ? "Queued for retry" : "Queued, not started yet";
            case PENDING -> pendingReason(se, type);
            case AWAITING_RETRY -> "Waiting to retry (attempt " + (se.getAttemptCount() + 1) + ")"
                    + (se.getDeadlineAt() != null ? " at " + se.getDeadlineAt() : "");
            case ERROR -> (se.getAttemptCount() > 0 ? "Failed on attempt " + (se.getAttemptCount() + 1) : "Failed")
                    + (error != null ? ": " + error : "");
            case TIMEOUT -> "Timed out" + (se.getDeadlineAt() != null ? " (deadline " + se.getDeadlineAt() + ")" : "");
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static String pendingReason(StepExecution se, StepType type) {
        if (se.getAwaitingMessageName() != null) {
            return "Waiting for message '" + se.getAwaitingMessageName() + "'"
                    + (se.getAwaitingCorrelationKey() != null ? " with key '" + se.getAwaitingCorrelationKey() + "'" : "");
        }
        if (type == StepType.TIMER && se.getDeadlineAt() != null) return "Waiting for timer until " + se.getDeadlineAt();
        if (type == StepType.USER_TASK) return "Waiting for a human task";
        if (type == StepType.PROCESS) return "Waiting for a child process to finish";
        if (se.getDeadlineAt() != null) return "Waiting (times out at " + se.getDeadlineAt() + ")";
        return "Waiting for its preconditions";
    }

    /** Deserialize the frozen step definition an execution ran with; null if it cannot be read. */
    private static io.mateu.workflow.domain.aggregates.Step safeStep(String stepJson) {
        try {
            return stepJson == null ? null : pojoFromJson(stepJson, io.mateu.workflow.domain.aggregates.Step.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "Process " + (id != null && id.length() > 5?"..." + id.substring(id.length() - 5):id);
    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<Trigger>();
        triggers.add(new OnLoadTrigger("refresh", 2000, 1, "state.status.type != 'SUCCESS'"));
        triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS'", 2000));
        return triggers;
    }

    @Toolbar(buttonStyle = ButtonStyle.secondary, buttonColor = ButtonColor.error)
    @Action(confirmationRequired = true)
    //@Hidden("state.status.type == 'SUCCESS' || state.status.type == 'DANGER'")
    public void cancelProcess() {
        // Requested, not performed here: this pod is whichever one served the click, and the
        // process belongs to the pod holding its partition. The view polls, so the state shows
        // up on the next refresh. Cancellation was always a notification anyway — whether a
        // worker abandons what it is doing is the worker's business.
        commandDispatcher.dispatch(new ProcessCancellationRequested(null, id));
    }

    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Action
    public void pauseProcess() {
        commandDispatcher.dispatch(new PauseProcessRequested(id));
    }

    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Action
    public void resumeProcess() {
        commandDispatcher.dispatch(new ResumeProcessRequested(id));
    }

    /**
     * Picks the process up where it stopped: the steps that failed (or were cancelled) run again,
     * the ones that succeeded are left alone. The usual choice when the failure was the
     * environment and the environment has since recovered.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Retry from failure")
    @Action
    public void retryProcess() {
        // Requested, not performed here: like cancel/pause/resume, the retry runs on the pod that
        // owns the process, so an operator can re-drive a failed process from its detail view
        // without dropping to the cross-process Steps page.
        commandDispatcher.dispatch(new RetryProcessRequested(id));
    }

    /**
     * Runs the whole process again from the top, including the steps that already succeeded. The
     * choice when the run itself was wrong rather than its surroundings — so it asks first, since
     * re-running a step that already did its work is not always free.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Restart from the beginning")
    @Action(confirmationRequired = true,
            confirmationMessage = "Every step runs again, including the ones that already "
                    + "succeeded. Continue?")
    public void restartProcess() {
        commandDispatcher.dispatch(new RestartProcessRequested(id));
    }


    @Action
    public Object refresh(HttpRequest httpRequest) {
        var id = (String) httpRequest.runActionRq().componentState().get("id");
        if (id != null) {
            var loaded = load(id, httpRequest);
            if (loaded instanceof URI uri) {
                return uri;
            }
            return new State(loaded);
        }
        return new State(this);
    }


    Status mapStepStatus(String rawStatus) {
        StepExecutionStatus status = StepExecutionStatus.valueOf(rawStatus);
        StatusType statusType = switch (status) {
            case CREATED -> StatusType.NONE;
            case PENDING -> StatusType.INFO;
            case RUNNING, AWAITING_RETRY -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case ERROR, TIMEOUT, CANCELLED -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        if ("cancelProcess".equals(memberName)) {
            return StatusType.SUCCESS.equals(status.type()) || StatusType.DANGER.equals(status.type());
        }
        if ("pauseProcess".equals(memberName)) {
            return processStatus != ProcessStatus.PENDING && processStatus != ProcessStatus.RUNNING;
        }
        if ("resumeProcess".equals(memberName)) {
            return processStatus != ProcessStatus.PAUSED;
        }
        // Both ways of running a stopped process again — where it failed, or from the top — offered
        // wherever the engine will accept them (see RetryProcessUseCase / RestartProcessUseCase).
        // A cancelled process is stopped, not finished, and picking it up again is a normal
        // operator move; COMPLETED and COMPENSATED are terminal by design and stay out of it.
        if ("retryProcess".equals(memberName) || "restartProcess".equals(memberName)) {
            return processStatus != ProcessStatus.ERROR && processStatus != ProcessStatus.CANCELLED;
        }
        return false;
    }
}
