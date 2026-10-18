package io.mateu.workflow.application.services;

import io.mateu.workflow.analysis.EventTriggerRules;
import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.EventWait;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.EventTriggerCatalog;
import io.mateu.workflow.domain.services.InboundEvents;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.template.Templates;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one place a domain event is received: matched against every definition's event
 * {@code triggers}, every {@code events.yaml} entry ({@link EventTriggerCatalog}) and every
 * {@code WAIT_FOR_MESSAGE}'s {@code onEvent}, de-duplicated, and turned into process starts and messages. Every channel ends here — the Kafka consumer, the REST and MCP inject,
 * the embedded API (call {@link #receive} or publish an {@link InboundEvent} as a Spring application
 * event) and, in embedded mode, the engine's own {@code PUBLISH_EVENT}s looped back.
 *
 * <ul>
 *   <li><b>Triggers</b>: for every definition that accepts new instances, every trigger — its own or an
 *       {@code events.yaml} entry naming it — whose destination and type match, not paused, whose filter holds — a {@link ProcessCreationRequested} as
 *       {@link AuthorizationContext#SYSTEM} through the {@link IngressRouter}, the door cron uses (so a
 *       paused definition creates it paused).</li>
 *   <li><b>Waits</b>: for every {@code onEvent} whose destination and type match and whose filter holds,
 *       a {@link MessageReceived} of the step's {@code messageName}, with the correlation key and
 *       variables it renders, through the {@link MessageDispatcher} — the door of the message API. Two
 *       steps that turn the event into the same message dispatch it once.</li>
 * </ul>
 *
 * <p><b>Idempotency</b>: each receiver ({@code trigger:<workflow>/<trigger>},
 * {@code wait:<message>/<key>}) records a receipt under the event's source and id once it acted, and is
 * skipped for a delivery it already has a receipt for. A wait's receipt is written where the message is
 * correlated, and only when a waiting step took it ({@code CorrelateMessageUseCase}): an event that
 * arrives before its step is waiting leaves no receipt, so its redelivery is tried again. The receipt is written after the hand-over, so a
 * failure to hand over leaves the event to be retried; the window between the two (a crash, a concurrent
 * duplicate) is closed by the default business key, derived from the event, and by a wait that, once
 * completed, no longer matches. A filter or template that fails records an ERROR receipt (under a key of
 * its own, so a later delivery is tried again) and that receiver does nothing.
 *
 * <p>A failure to hand over (a broker that is down) propagates: the caller — the consumer, the REST
 * request — retries the whole event, and the receivers that already acted are skipped by their receipts.
 */
@Service
@Slf4j
public class InboundEventService {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final EventTriggerRepository fileTriggers;
    private final EventReceiptRepository receipts;
    private final TriggerPauseRepository pauses;
    private final IngressRouter ingressRouter;
    private final MessageDispatcher messageDispatcher;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final ProcessRepository processes;

    public InboundEventService(WorkflowDefinitionRepository workflowDefinitionRepository,
                               EventTriggerRepository fileTriggers,
                               EventReceiptRepository receipts,
                               TriggerPauseRepository pauses,
                               IngressRouter ingressRouter,
                               MessageDispatcher messageDispatcher,
                               ObjectProvider<MeterRegistry> meterRegistry,
                               ProcessRepository processes) {
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.fileTriggers = fileTriggers;
        this.receipts = receipts;
        this.pauses = pauses;
        this.ingressRouter = ingressRouter;
        this.messageDispatcher = messageDispatcher;
        this.meterRegistry = meterRegistry;
        this.processes = processes;
    }

    /**
     * What receiving one event did.
     *
     * @param receipts   what each receiver that acted did: STARTED, DELIVERED or ERROR
     * @param duplicates the receivers that had already used this event, which did nothing this time
     */
    public record Result(String destination, String eventId, String eventSource, List<EventReceipt> receipts,
                         List<String> duplicates) {

        /** How many processes this delivery started. */
        public long started() {
            return receipts.stream().filter(r -> r.outcome() == EventReceipt.Outcome.STARTED).count();
        }

        /** How many messages this delivery dispatched to waiting steps. */
        public long delivered() {
            return receipts.stream().filter(r -> r.outcome() == EventReceipt.Outcome.DELIVERED).count();
        }
    }

    /** A Spring application event carrying an {@link InboundEvent} is received like a call to {@link #receive}. */
    @EventListener
    public void on(InboundEvent event) {
        receive(event);
    }

    /**
     * Receives one event.
     *
     * @throws IllegalArgumentException when it has no destination, id or source
     */
    public Result receive(InboundEvent event) {
        Objects.requireNonNull(event, "event");
        if (blank(event.destination()) || blank(event.id()) || blank(event.source())) {
            throw new IllegalArgumentException("A domain event needs a destination, an id and a source"
                    + " (the CloudEvents id and source are what make a redelivery a duplicate).");
        }
        var context = InboundEvents.context(event);
        var definitions = workflowDefinitionRepository.findAll();
        var acted = new ArrayList<EventReceipt>();
        var duplicates = new ArrayList<String>();
        var paused = pauses.findAll().keySet();
        var catalog = EventTriggerCatalog.of(definitions, fileTriggers == null ? List.of() : fileTriggers.findAll());
        for (var entry : catalog) {
            if (entry.fires() && matches(entry.trigger().destination(), entry.trigger().type(), event)) {
                start(entry.definition(), entry.trigger(), event, context, paused, acted, duplicates);
            }
        }
        deliver(definitions, event, context, acted, duplicates);
        acted.forEach(receipt -> count(event, receipt.outcome().name()));
        duplicates.forEach(receiver -> count(event, "DUPLICATE"));
        if (acted.isEmpty() && duplicates.isEmpty()) {
            log.debug("Event {} ({}) on '{}' matched no trigger and no waiting step", event.id(), event.type(), event.destination());
        }
        return new Result(event.destination(), event.id(), event.source(), List.copyOf(acted), List.copyOf(duplicates));
    }

    private void start(WorkflowDefinition definition, EventTrigger trigger, InboundEvent event, Map<String, Object> context,
                       Set<String> paused, List<EventReceipt> acted, List<String> duplicates) {
        if (definition.id() == null || !definition.status().accceptsNewInstances()) {
            log.info("Event {} matches trigger '{}' of workflow '{}', which is {}: nothing started",
                    event.id(), trigger.id(), definition.id(), definition.status());
            return;
        }
        if (paused.contains(TriggerPause.keyOf(definition.id(), trigger.id()))) {
            log.info("Event {} matches trigger '{}' of workflow '{}', which is paused: nothing started",
                    event.id(), trigger.id(), definition.id());
            return;
        }
        var receiver = "trigger:" + definition.id() + "/" + trigger.id();
        var receiptKey = InboundEvents.receiptKey(receiver, event);
        if (receipts.exists(receiptKey)) {
            log.info("Event {} from {} already started a process through trigger '{}' of '{}': ignored",
                    event.id(), event.source(), trigger.id(), definition.id());
            duplicates.add(receiver);
            return;
        }
        String businessKey;
        Map<String, String> variables;
        try {
            if (!InboundEvents.accepts(trigger.filter(), context)) {
                return;
            }
            businessKey = InboundEvents.text(trigger.businessKey(), context);
            if (businessKey == null) {
                businessKey = InboundEvents.defaultBusinessKey(definition.id(), trigger.id(), event);
            }
            variables = InboundEvents.variables(trigger.variables(), context);
        } catch (Templates.TemplateException e) {
            log.warn("Trigger '{}' of workflow '{}' could not read event {}: {}", trigger.id(), definition.id(),
                    event.id(), e.getMessage());
            var receipt = receipt(receiver + "#error", receiver, EventReceipt.Kind.TRIGGER, event, EventReceipt.Outcome.ERROR,
                    definition.id(), trigger.id(), null, null, null, e.getMessage());
            receipts.save(receipt);
            acted.add(receipt);
            return;
        }
        var taken = takenByAnotherWorkflow(businessKey, definition.id());
        if (taken != null) {
            // The creation would be ignored (a business key is unique across workflows): say so instead of
            // recording a start that never happens. Under the error key, so a later delivery is tried again.
            var detail = "business key '" + businessKey + "' is already used by a process of workflow '" + taken
                    + "': nothing started (a business key is unique across workflows — put the workflow in the"
                    + " trigger's businessKey template)";
            log.warn("Trigger '{}' of workflow '{}' did not start a process for event {}: {}", trigger.id(),
                    definition.id(), event.id(), detail);
            var receipt = receipt(receiver + "#error", receiver, EventReceipt.Kind.TRIGGER, event, EventReceipt.Outcome.ERROR,
                    definition.id(), trigger.id(), businessKey, null, null, detail);
            receipts.save(receipt);
            acted.add(receipt);
            return;
        }
        log.info("Event {} ({}) on '{}' starts workflow '{}' through trigger '{}' (business key {})",
                event.id(), event.type(), event.destination(), definition.id(), trigger.id(), businessKey);
        // The engine acting for itself, as for cron: SYSTEM, so flow-authorization requirements do not
        // stop a workflow from starting on the events it declares.
        ingressRouter.route(new ProcessCreationRequested(definition.id(), businessKey, toVariables(variables),
                null, AuthorizationContext.SYSTEM));
        var receipt = receipt(receiver, receiver, EventReceipt.Kind.TRIGGER, event, EventReceipt.Outcome.STARTED,
                definition.id(), trigger.id(), businessKey, null, null, null);
        receipts.save(receipt);
        acted.add(receipt);
    }

    /**
     * The workflow of the process that already holds {@code businessKey}, when it is not
     * {@code workflowId}; null otherwise. Creation is asynchronous (the ingress only hands the request
     * over), so this is the check the receipt can make: a key held by the same workflow is the idempotent
     * collapse of a redelivery, one held by another workflow means nothing will start. Best effort — a
     * process created concurrently, or on another shard, is not seen.
     */
    private String takenByAnotherWorkflow(String businessKey, String workflowId) {
        if (processes == null || businessKey == null || businessKey.isBlank()) {
            return null;
        }
        return processes.findByBusinessKey(businessKey)
                .map(io.mateu.workflow.domain.aggregates.Process::getWorkflowDefinitionId)
                .filter(other -> other != null && !other.equals(workflowId))
                .orElse(null);
    }

    /** One message to dispatch: the step's message name, the key and variables the event rendered. */
    private record Delivery(String messageName, String correlationKey, Map<String, String> variables, String workflowId) {
    }

    private void deliver(List<WorkflowDefinition> definitions, InboundEvent event, Map<String, Object> context,
                         List<EventReceipt> acted, List<String> duplicates) {
        var deliveries = new LinkedHashMap<String, Delivery>();
        for (var definition : definitions) {
            for (var definedStep : definition.steps()) {
                // A WAIT_FOR_MESSAGE's onEvent, or the onEvent of an HTTP_CALL / PUBLISH_EVENT awaitReply.
                if (!definedStep.awaitsMessage()) {
                    continue;
                }
                var step = definedStep.messageWait();
                EventWait wait = step.onEvent();
                if (wait == null || blank(step.messageName())
                        || !matches(wait.destination(), wait.type(), event)) {
                    continue;
                }
                var receiver = "wait:" + step.messageName();
                try {
                    if (!InboundEvents.accepts(wait.filter(), context)) {
                        continue;
                    }
                    var key = InboundEvents.correlationKey(wait.correlationKey(), event, context);
                    if (key == null) {
                        throw new Templates.TemplateException("the event carries no correlation key (no key, no subject"
                                + " and no correlationKey template)", null);
                    }
                    var variables = InboundEvents.variables(wait.variables(), context);
                    var delivery = new Delivery(step.messageName(), key, variables, definition.id());
                    deliveries.putIfAbsent(step.messageName() + "\n" + key, delivery);
                } catch (Templates.TemplateException e) {
                    log.warn("Step '{}' of workflow '{}' could not read event {}: {}", step.id(), definition.id(),
                            event.id(), e.getMessage());
                    var receipt = receipt(receiver + "#" + definition.id() + "/" + step.id() + "#error", receiver,
                            EventReceipt.Kind.WAIT, event, EventReceipt.Outcome.ERROR, definition.id(), null, null,
                            step.messageName(), null, e.getMessage());
                    receipts.save(receipt);
                    acted.add(receipt);
                }
            }
        }
        for (var delivery : deliveries.values()) {
            var receiver = "wait:" + delivery.messageName() + "/" + delivery.correlationKey();
            var receiptKey = InboundEvents.receiptKey(receiver, event);
            if (receipts.exists(receiptKey)) {
                log.info("Event {} from {} was already delivered as message '{}' with key '{}': ignored",
                        event.id(), event.source(), delivery.messageName(), delivery.correlationKey());
                duplicates.add(receiver);
                continue;
            }
            log.info("Event {} ({}) on '{}' delivered as message '{}' with correlation key '{}'", event.id(),
                    event.type(), event.destination(), delivery.messageName(), delivery.correlationKey());
            messageDispatcher.dispatch(new MessageReceived(delivery.messageName(), delivery.correlationKey(),
                    toVariables(delivery.variables()),
                    new MessageReceived.EventOrigin(event.destination(), event.type(), event.source(), event.id())));
            // Not saved here: whether a step was waiting is only known where the message is correlated
            // (asynchronously, maybe on another shard), and a receipt for a message nobody took would
            // refuse the redelivery that is the remedy for an event arriving before its step waits.
            // CorrelateMessageUseCase writes this receipt when a waiting step takes the message.
            acted.add(receipt(receiver, receiver, EventReceipt.Kind.WAIT, event, EventReceipt.Outcome.DELIVERED,
                    delivery.workflowId(), null, null, delivery.messageName(), delivery.correlationKey(),
                    "dispatched; recorded once a waiting step takes it"));
        }
    }

    private static boolean matches(String destination, String type, InboundEvent event) {
        return destination != null && destination.equals(event.destination())
                && EventTriggerRules.typeMatches(type, event.type());
    }

    private static EventReceipt receipt(String keyReceiver, String receiver, EventReceipt.Kind kind, InboundEvent event,
                                        EventReceipt.Outcome outcome, String workflowId, String triggerId,
                                        String businessKey, String messageName, String correlationKey, String detail) {
        return new EventReceipt(InboundEvents.receiptKey(keyReceiver, event), receiver, kind, event.destination(),
                event.type(), event.source(), event.id(), outcome, workflowId, triggerId, businessKey, messageName,
                correlationKey, detail, Instant.now());
    }

    private static List<Variable> toVariables(Map<String, String> variables) {
        return variables.entrySet().stream().map(e -> new Variable(e.getKey(), e.getValue())).toList();
    }

    private void count(InboundEvent event, String outcome) {
        var registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter("eventconductor.events.received", "destination", event.destination(), "outcome", outcome)
                    .increment();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
