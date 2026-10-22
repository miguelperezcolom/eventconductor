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
// A manual operation's dialog closes with this event: repaint, so the operator sees what it did.
@SubscribeTo(event = io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.OPERATED_EVENT,
        action = "refresh", source = SubscriptionSource.DOCUMENT)
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

    /** Field-injected and optional, so the constructor (and whoever builds this by hand) is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @Hidden
    io.mateu.workflow.application.out.InvocationRepository invocationRepository;

    /** Field-injected and optional, like the invocations: the updates of the process, for the Updates tab. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @Hidden
    io.mateu.workflow.application.out.ProcessUpdateRepository processUpdateRepository;


    String id;

    String name;

    Status status;

    /**
     * Whether this process was invoked synchronously and whether it has answered its caller — a
     * badge next to the status. Hidden for a process nobody is waiting on.
     */
    Status invocation;

    /**
     * CONTINUE_AS_NEW (2.33): the run this one continues, and the run that continues it — the ids of the
     * neighbouring runs of the chain, under the same business key. Hidden when there are none.
     */
    @Label("Continues run")
    String continuedFrom;

    @Label("Continued as run")
    String continuedAs;

    /** Raw domain status, kept for the pause/resume toolbar visibility rules (the badge shows {@link #status}). */
    @Hidden
    ProcessStatus processStatus;

    /** Whether the process's definition declares sensitive variables — what shows the Reveal action. */
    @Hidden
    boolean hasSensitiveVariables;

    /**
     * Where the "back" action goes, carried in from the query string. Plumbing, like
     * {@link #processStatus} above it — it says nothing about the process, and read-only it was
     * still a labelled field on screen showing a URL to somebody who did not ask for one.
     */
    @Hidden
    String returnTo;

    // Explicit tab names: since mateu 379440d83 consecutive @Tab annotations with the SAME
    // value (and bare @Tab means value "") are merged into one tab, which stacked these four
    // lists vertically. Distinct values keep one tab per list.
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

    /**
     * The manual operations performed on this process — who, when, what and why — newest first. The
     * same lines are in the process log; here they are on their own, because they are the first thing
     * to read when a process did something its definition does not explain.
     */
    @Tab("Operations")
    @Label("")
    List<Message> operations;

    /**
     * The updates the process received (see {@code updates} in its definition) — oldest first, with who
     * asked, how each ended and what it answered; payloads as recorded, their sensitive fields masked.
     * Hidden for a process that has had none.
     */
    @Tab("Updates")
    @Label("")
    List<io.mateu.workflow.infra.in.ui.pages.process.childcruds.UpdateEntry> updates;

    @Tab
    @Label("")
    List<Variable> variables;

    // What the process answered (a REPLY step, or the engine on its behalf) — see ProcessReply.
    // Hidden entirely for a process that has not replied.
    @Tab("Reply")
    @Label("Outcome")
    String replyOutcome;

    @Tab("Reply")
    @Label("Compensation")
    String replyCompensation;

    @Tab("Reply")
    @Label("Replied at")
    LocalDateTime repliedAt;

    @Tab("Reply")
    @Label("REPLY step")
    String replyStep;

    @Tab("Reply")
    @Label("Error")
    String replyError;

    @Tab("Reply")
    @Label("Payload")
    @Stereotype(FieldStereotype.textarea)
    String replyPayload;

    // Last: what an operator opens a process for is what ran and what failed — the steps, first.
    // The diagram is the wide canvas to look at the shape when that is the question.
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

    public Object load(String id, HttpRequest httpRequest) {
        this.id = id;
        Process process = processRepository.findById(id).orElse(processRepository.findByBusinessKey(id).orElse(null));
        this.name = process.getName();
        this.processStatus = process.getStatus();
        this.hasSensitiveVariables = !io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process).isEmpty();
        this.status = mapProcessStatus(process.getStatus(), process.getCompletionPercentage());
        var stepExecutions = stepExecutionRepository.findByProcess(process);
        // What ran and the certain path ahead, not every step the process was created with — see StepHistory.
        this.steps = StepHistory.visible(stepExecutions, process.getStatus()).stream()
                .map(entry -> {
                    var se = entry.execution();
                    return new Step(id, se.id(), rowName(se), stepRowStatus(entry),
                            Step.format(se.getStartedAt()), Step.format(se.getFinishedAt()));
                })
                .toList();
        var logs = logMessageRepository.findByProcessId(id);
        this.diagram = buildDiagram(process, stepExecutions, logs);
        this.messages = logs.stream()
                .filter(msg -> !MessageType.isError(msg.getMessageType()))
                .sorted(Comparator.comparing(LogMessage::getTimestamp).reversed())
                .limit(10)
                .map(msg -> new Message(id, msg.id(), msg.getTimestamp(), msg.getMessage()))
                .toList();
        this.operations = logs.stream()
                .filter(io.mateu.workflow.application.usecases.process.operate.OperatorAudit::isOperatorLine)
                .sorted(Comparator.comparing(LogMessage::getTimestamp).reversed())
                .limit(50)
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
        // Sensitive values read as the mask; the Reveal action is the audited way to read one.
        this.variables = io.mateu.workflow.application.services.SensitiveVariableMasking.variables(process).stream().map(variable -> new Variable(variable.name(), variable.value())).toList();
        this.continuedFrom = process.getContinuedFromProcessId();
        this.continuedAs = process.getContinuedAsProcessId();
        if (this.continuedAs != null) {
            // COMPLETED, handed over: say so on the badge rather than as a plain completion.
            this.status = new Status(StatusType.SUCCESS, "Continued as new");
        }
        loadReply(process);
        this.updates = updateEntries(id, processUpdateRepository,
                io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process));
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
     * The process's updates as the Updates tab lists them, oldest first; empty without a repository. Shown as
     * every door shows them: sealed payload fields and a sealed result masked (see {@code ProcessUpdateService#shown}).
     */
    static List<io.mateu.workflow.infra.in.ui.pages.process.childcruds.UpdateEntry> updateEntries(
            String processId, io.mateu.workflow.application.out.ProcessUpdateRepository repository,
            io.mateu.workflow.crypto.SensitiveNames sensitive) {
        if (repository == null) {
            return List.of();
        }
        return repository.findByProcessId(processId).stream()
                .map(io.mateu.workflow.application.updates.ProcessUpdateService::shown)
                .map(update -> update.result() == null ? update : new io.mateu.workflow.application.updates.ProcessUpdate(
                        update.id(), update.processId(), update.workflowDefinitionId(), update.name(),
                        update.idempotencyKey(), update.requestHash(), update.payload(), update.status(),
                        io.mateu.workflow.application.updates.ProcessUpdateService.maskMembers(update.result(), sensitive),
                        update.error(), update.requestedBy(), update.withStart(), update.requestedAt(),
                        update.completedAt(), update.expiresAt()))
                .map(update -> new io.mateu.workflow.infra.in.ui.pages.process.childcruds.UpdateEntry(processId,
                        update.id(), update.requestedAt(), update.name(),
                        update.requestedBy() == null ? "anonymous" : update.requestedBy(),
                        toUpperCaseFirst(update.status().name().toLowerCase().replace('_', ' '))
                                + (update.withStart() ? " (with start)" : ""),
                        update.payload(),
                        update.result() != null ? update.result() : update.error()))
                .toList();
    }

    /** Fills the invocation badge and the Reply tab from the process's reply and its invocation. */
    void loadReply(Process process) {
        var reply = process.getReply();
        var invoked = invocationRepository != null && invocationRepository.findByProcessId(process.getId()).isPresent();
        if (invoked) {
            this.invocation = reply == null
                    ? new Status(StatusType.INFO, "Synchronous · waiting to reply")
                    : new Status(reply.isFailure() ? StatusType.DANGER : StatusType.SUCCESS,
                            "Synchronous · " + toUpperCaseFirst(reply.outcome().name().toLowerCase().replace('_', ' ')));
        }
        if (reply != null) {
            this.replyOutcome = reply.outcome().name();
            this.replyCompensation = reply.compensation().name();
            this.repliedAt = reply.repliedAt();
            this.replyStep = reply.stepId();
            this.replyError = reply.error();
            this.replyPayload = io.mateu.workflow.application.services.SensitiveVariableMasking.json(reply.payload(), io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process));
        }
    }

    /**
     * The read-only graph for this process, overlaid with each step's live state and an "active"
     * highlight on the running step(s) — so the diagram shows where the process currently is.
     */
    Element buildDiagram(Process process, List<StepExecution> stepExecutions, List<LogMessage> logs) {
        var def = definitionOf(process);
        if (def == null) return null;
        var projection = io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.project(def, process, stepExecutions, logs);
        var overlay = projection.overlay();
        var attrs = new HashMap<String, String>();
        attrs.put("import", GRAPH_MODULE);
        // The diagram must show the process's ACTUAL step set, not just the definition: steps a
        // DYNAMIC step injected at runtime are not in the definition, so a value built from it alone
        // would render a graph the running process never had. Feed the union — declared steps plus
        // the injected ones (each execution carries its own frozen stepJson) — so injected nodes
        // render with their real preconditions. The plain definition-editor view is untouched: it
        // renders the definition directly and never comes through here.
        this.processGraph = toJson(projection.definition());
        this.processGraphOverlay = overlay.isEmpty() ? "" : toJson(overlay);
        // Where to read it, not the thing itself — see the fields' own note. The topology travels
        // this way too and not only the overlay: a DYNAMIC step injects nodes while the process
        // runs, so the graph's shape changes under a page that is already open.
        attrs.put("value", "${state.processGraph}");
        attrs.put("readonly", "true");
        if (!overlay.isEmpty()) attrs.put("overlay", "${state.processGraphOverlay}");
        // Give the graph a viewport-sized box. Inside a tab the host has no height context and falls
        // back to its ~230px min-height, far too short for monitoring a live process. A fixed 68vh
        // overflowed the process view — the graph sits under a header, a process summary and the tab
        // strip (~28rem of chrome), so 68vh + that chrome scrolled the page. Sizing the graph to the
        // space left below the chrome (calc) fills the viewport and keeps the whole view on screen.
        return Element.builder()
                .name(GRAPH_TAG)
                .attributes(attrs)
                .content("")
                .style("display: block; height: calc(100vh - 30rem); min-height: 320px;")
                .build();
    }

    /**
     * The definition <em>this</em> process is running: the copy frozen into it when it was created,
     * not whatever the engine holds under that id today.
     *
     * <p>A definition is editable, and every process that started before an edit keeps running the
     * version it started with — the engine never re-reads it, because each step execution carries
     * its own frozen {@code stepJson}. Drawing the diagram from the repository therefore drew a
     * process that does not exist: steps added since appeared as nodes that never ran, steps
     * removed since vanished under the executions that did run, and a rewired precondition drew
     * edges the instance never followed. Deleting the definition erased the diagram of every
     * finished process that used it.
     *
     * <p>The snapshot is authoritative here; the repository is only a fallback, for a process
     * stored before {@code workflowDefinitionJson} was filled in, or one whose snapshot cannot be
     * read back.
     */
    io.mateu.workflow.domain.aggregates.WorkflowDefinition definitionOf(Process process) {
        var snapshot = safeDefinition(process.getWorkflowDefinitionJson());
        if (snapshot != null) {
            return snapshot;
        }
        return workflowDefinitionRepository.findById(process.getWorkflowDefinitionId()).orElse(null);
    }

    private static io.mateu.workflow.domain.aggregates.WorkflowDefinition safeDefinition(String definitionJson) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.safeDefinition(definitionJson);
    }

    static io.mateu.workflow.domain.aggregates.WorkflowDefinition withInjectedSteps(
            io.mateu.workflow.domain.aggregates.WorkflowDefinition def, List<StepExecution> stepExecutions) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.withInjectedSteps(def, stepExecutions);
    }

    static String rowName(StepExecution se) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.rowName(se);
    }

    static void putRunsAndIterations(Map<String, Object> entry, StepExecution current, List<StepExecution> all) {
        io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.putRunsAndIterations(entry, current, all);
    }

    static String overlayState(StepExecutionStatus status) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.overlayState(status);
    }

    static int statusRank(StepExecutionStatus status) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.statusRank(status);
    }

    static Map<String, Object> overlayEntry(StepExecution se, String error) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.overlayEntry(se, error);
    }

    static Map<String, Object> overlayEntry(StepExecution se, String error, io.mateu.workflow.crypto.SensitiveNames sensitive) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.overlayEntry(se, error, sensitive);
    }

    static Map<String, Integer> executionOrder(Collection<StepExecution> executions) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.executionOrder(executions);
    }

    private static LocalDateTime whenItRan(StepExecution se) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.whenItRan(se);
    }

    static Map<String, String> latestErrorByStepExecution(List<LogMessage> logs) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.latestErrorByStepExecution(logs);
    }

    static String reasonFor(StepExecution se, io.mateu.workflow.domain.aggregates.Step step, String error) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.reasonFor(se, step, error);
    }

    private static String pendingReason(StepExecution se, StepType type) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.pendingReason(se, type);
    }

    private static io.mateu.workflow.domain.aggregates.Step safeStep(String stepJson) {
        return io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection.safeStep(stepJson);
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


    /**
     * Changes the variables of the process by hand — an admin operation, recorded with the reason.
     * Opens a dialog; see {@code SetProcessVariablesUseCase}.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Set variables")
    @Action
    public Object setVariables() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.dialog("Set variables",
                new io.mateu.workflow.infra.in.ui.pages.process.operate.SetVariablesForm()
                        .forProcess(id));
    }

    /**
     * Reveals one sensitive variable's value — for a caller holding a reveal role, with a reason, audited.
     * Opens a dialog; see {@code SensitiveVariableRevealService}.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Reveal variable")
    @Action
    public Object revealVariable() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.dialog("Reveal a sensitive variable",
                new io.mateu.workflow.infra.in.ui.pages.process.operate.RevealVariableForm()
                        .forProcess(id));
    }

    /**
     * Completes a step by hand, as if its worker had answered — an admin operation, recorded with the
     * reason. Opens a dialog; see {@code CompleteStepManuallyUseCase}.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Complete step")
    @Action
    public Object completeStep() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.dialog("Complete a step by hand",
                new io.mateu.workflow.infra.in.ui.pages.process.operate.CompleteStepForm()
                        .forProcess(id));
    }

    /**
     * Runs the process again from one of its steps — an admin operation, recorded with the reason.
     * Opens a dialog; see {@code RetryFromStepUseCase}.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Retry from step")
    @Action
    public Object retryFromStep() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.dialog("Run again from a step",
                new io.mateu.workflow.infra.in.ui.pages.process.operate.RetryFromStepForm()
                        .forProcess(id));
    }

    /**
     * Moves the process onto another version of its definition — an admin operation, recorded with the
     * reason. Opens a dialog with a dry-run preview and the step mapping; see {@code MigrateProcessUseCase}.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Migrate…")
    @Action
    public Object migrate() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.dialog("Migrate to another version",
                new io.mateu.workflow.infra.in.ui.pages.process.operate.MigrateForm()
                        .forProcess(id));
    }

    /**
     * Copies the process's state to the clipboard as JSON — the identity, status, variables, steps
     * and the message/error log — so it can be pasted into a ticket or a chat without screenshotting
     * a screen a paste cannot carry.
     *
     * <p>The clipboard is the browser's, which the server cannot reach, so the JSON is handed to the
     * page as a {@code DispatchEvent} the frontend turns into a {@code navigator.clipboard} write and
     * a "copied" toast (see {@code eventconductor-workflow-graph}'s clipboard bridge). Read fresh from
     * the store rather than from the bound state, so it is the process as it is now, not as the tab
     * last painted it.
     */
    @Toolbar(buttonStyle = ButtonStyle.secondary)
    @Label("Copy state")
    @Action
    public Object copyState(HttpRequest httpRequest) {
        var processId = (String) httpRequest.runActionRq().componentState().get("id");
        if (processId == null) {
            processId = id;
        }
        var process = processRepository.findById(processId)
                .orElse(processRepository.findByBusinessKey(processId).orElse(null));
        if (process == null) {
            return new State(this);
        }
        return UICommand.builder()
                .type(UICommandType.DispatchEvent)
                .data(new DispatchEventData("ec-copy-to-clipboard", Map.of(
                        "text", toJson(stateSnapshot(process)),
                        "message", "Process state copied to the clipboard")))
                .build();
    }

    /** The process's state as a plain, pasteable value — the shape {@link #copyState} serialises. */
    private ProcessStateSnapshot stateSnapshot(Process process) {
        var stepExecutions = stepExecutionRepository.findByProcess(process);
        var logs = logMessageRepository.findByProcessId(process.id());
        return new ProcessStateSnapshot(
                process.id(),
                process.getName(),
                process.getBusinessKey(),
                process.getStatus().name(),
                process.getCompletionPercentage(),
                io.mateu.workflow.application.services.SensitiveVariableMasking.variables(process).stream()
                        .map(v -> new Variable(v.name(), v.value())).toList(),
                stepExecutions.stream()
                        .map(se -> new StepStateSnapshot(se.getStepId(), se.getStatus().name(),
                                Step.format(se.getStartedAt()), Step.format(se.getFinishedAt())))
                        .toList(),
                logs.stream()
                        .sorted(Comparator.comparing(LogMessage::getTimestamp))
                        .map(m -> new LogStateSnapshot(
                                m.getTimestamp() == null ? null : m.getTimestamp().toString(),
                                m.getMessageType(), m.getMessage()))
                        .toList());
    }

    private record ProcessStateSnapshot(String id, String name, String businessKey, String status,
                                        int completionPercentage, List<Variable> variables,
                                        List<StepStateSnapshot> steps, List<LogStateSnapshot> log) {
    }

    private record StepStateSnapshot(String stepId, String status, String startedAt, String finishedAt) {
    }

    private record LogStateSnapshot(String timestamp, String type, String message) {
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


    /**
     * A listed step's badge. A CREATED step is either the next thing the run will certainly do
     * («Next») or one queued again for a retry, which is waiting work («Pending») — never «Created»,
     * which only ever said that the engine had materialised the row.
     */
    Status stepRowStatus(StepHistory.Entry entry) {
        var se = entry.execution();
        if (se.getStatus() == StepExecutionStatus.CREATED) {
            return entry.ahead()
                    ? new Status(StatusType.NONE, "Next")
                    : new Status(StatusType.INFO, "Pending");
        }
        return mapStepStatus(se.getStatus().name());
    }

    Status mapStepStatus(String rawStatus) {
        StepExecutionStatus status = StepExecutionStatus.valueOf(rawStatus);
        StatusType statusType = switch (status) {
            case REVIEW_REQUIRED -> StatusType.WARNING;
            case CREATED -> StatusType.NONE;
            case PENDING, WAITING_ON_LOCK -> StatusType.INFO;
            case RUNNING, AWAITING_RETRY -> StatusType.WARNING;
            case COMPLETED -> StatusType.SUCCESS;
            case ERROR, TIMEOUT, CANCELLED -> StatusType.DANGER;
        };
        return new Status(statusType, toUpperCaseFirst(status.name()));
    }

    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        if ("invocation".equals(memberName)) {
            return invocation == null;
        }
        if ("continuedFrom".equals(memberName)) {
            return continuedFrom == null;
        }
        if ("continuedAs".equals(memberName)) {
            return continuedAs == null;
        }
        if (memberName != null && memberName.startsWith("repl")) {
            // The whole Reply tab: nothing to show until the process has answered.
            return replyOutcome == null;
        }
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
        if ("setVariables".equals(memberName) || "completeStep".equals(memberName)
                || "retryFromStep".equals(memberName) || "migrate".equals(memberName)) {
            // The manual operations: a live process only (ProcessOperationRules.LIVE).
            return processStatus == null
                    || !io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules.LIVE.contains(processStatus);
        }
        if ("revealVariable".equals(memberName)) {
            // Only where there is something to reveal.
            return !hasSensitiveVariables;
        }
        if ("updates".equals(memberName)) {
            return updates == null || updates.isEmpty();
        }
        if ("operations".equals(memberName)) {
            return operations == null || operations.isEmpty();
        }
        if ("retryProcess".equals(memberName) || "restartProcess".equals(memberName)) {
            return processStatus != ProcessStatus.ERROR && processStatus != ProcessStatus.CANCELLED;
        }
        return false;
    }
}
