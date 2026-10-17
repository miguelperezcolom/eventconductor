package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.infra.out.memory.InMemoryEventReceiptRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The event trigger tools: names pinned, every one registered by Spring AI, and what they do. */
class EventTriggerMcpToolsTest {

    private static final String[] PUBLISHED_TOOLS = {
            "listEventTriggers",
            "pauseEventTrigger",
            "resumeEventTrigger",
            "listReceivedEvents",
            "publishInboundEvent",
            "createEventTrigger",
            "updateEventTrigger",
            "deleteEventTrigger",
            "exportEventTriggerYaml",
    };

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final IngressRouter ingressRouter = mock(IngressRouter.class);
    EventTriggerMcpTools tools;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(definitions.findAll()).thenReturn(List.of(
                new WorkflowDefinition("onboarding", "Onboarding", 1, null, false, 0, false, null, 0, List.of())
                        .withTriggers(List.of(new EventTrigger("on-created", "bookings", "com.acme.booking.created",
                                null, "b-${data.bookingId}", null)))));
        var properties = new EventDestinationsProperties();
        var destination = new EventDestinationsProperties.Destination();
        destination.setTopic("booking-events");
        properties.getDestinations().put("bookings", destination);
        var receipts = new InMemoryEventReceiptRepository();
        var pauses = new InMemoryTriggerPauseRepository();
        var inbound = new InboundEventService(definitions, new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository(), receipts, pauses, ingressRouter, mock(MessageDispatcher.class),
                mock(ObjectProvider.class), null);
        tools = new EventTriggerMcpTools(new EventTriggerService(definitions, pauses, receipts, properties, new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository()), inbound);
    }

    @Test
    void thePublishedToolsAreExactlyTheOnesTheContractNames() {
        assertThat(Arrays.stream(EventTriggerMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName))
                .containsExactlyInAnyOrder(PUBLISHED_TOOLS);
    }

    @Test
    void theVariablesParameterSaysWhatNullAndAnEmptyObjectMean() {
        // The service: null = data's top-level members, {} = none. An agent must be told exactly that.
        for (var name : List.of("createEventTrigger", "updateEventTrigger")) {
            var method = Arrays.stream(EventTriggerMcpTools.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            var variables = Arrays.stream(method.getParameters()).filter(p -> p.getName().equals("variables"))
                    .findFirst().orElseThrow();
            var description = variables.getAnnotation(org.springframework.ai.tool.annotation.ToolParam.class).description();
            assertThat(description).contains("null: every top-level member of data").contains("{}: the process gets no variables");
        }
    }

    @Test
    void springAiRegistersEveryTool() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder(PUBLISHED_TOOLS);
        assertThat(Arrays.stream(callbacks)).allSatisfy(c -> assertThat(c.getToolDefinition().description()).isNotBlank());
    }

    @Test
    void listsOperatesAndInjects() {
        var trigger = tools.listEventTriggers(null).getFirst();
        assertThat(trigger.key()).isEqualTo("onboarding/on-created");
        assertThat(trigger.topic()).isEqualTo("booking-events");
        assertThat(trigger.state()).isEqualTo(EventTriggerService.TriggerState.ACTIVE);

        assertThat(tools.pauseEventTrigger("onboarding", "on-created").pausedBy()).isEqualTo("mcp");
        assertThat(tools.publishInboundEvent("bookings", "com.acme.booking.created", "{\"bookingId\":\"B-1\"}", "e-1",
                "pms", null, null).started()).isZero();
        assertThat(tools.resumeEventTrigger("onboarding", "on-created").state())
                .isEqualTo(EventTriggerService.TriggerState.ACTIVE);

        var result = tools.publishInboundEvent("bookings", "com.acme.booking.created", "{\"bookingId\":\"B-1\"}",
                "e-1", "pms", null, null);
        assertThat(result.started()).isEqualTo(1);
        assertThat(result.receipts().getFirst().businessKey()).isEqualTo("b-B-1");
        assertThat(tools.publishInboundEvent("bookings", "com.acme.booking.created", "{\"bookingId\":\"B-1\"}",
                "e-1", "pms", null, null).duplicates()).containsExactly("trigger:onboarding/on-created");
        verify(ingressRouter, times(1)).route(any(ProcessCreationRequested.class));

        assertThat(tools.listReceivedEvents("onboarding", null)).extracting(EventReceipt::outcome)
                .containsExactly(EventReceipt.Outcome.STARTED);
    }

    @Test
    void aTriggerIsCreatedEditedExportedAndDeletedThroughTheTools() {
        var onboarding = definitions.findAll().get(0);
        when(definitions.findById("onboarding")).thenReturn(java.util.Optional.of(onboarding));

        var created = tools.createEventTrigger("cdc", "onboarding", "customers-cdc", "*", "data.op == 'u'", null,
                java.util.Map.of("customerId", "${data.after.id}"));
        assertThat(created.origin()).isEqualTo("API");
        assertThat(created.editable()).isTrue();
        assertThat(tools.updateEventTrigger("onboarding", "cdc", "customers-cdc", "io.debezium.*", null, null, null).type())
                .isEqualTo("io.debezium.*");
        assertThat(tools.exportEventTriggerYaml("onboarding", "cdc")).contains("- id: cdc", "workflow: onboarding");
        assertThat(tools.deleteEventTrigger("onboarding", "cdc")).isEqualTo("Trigger onboarding/cdc deleted");
        assertThat(tools.listEventTriggers("onboarding")).extracting(v -> v.triggerId()).containsExactly("on-created");
    }

    private EventTriggerMcpTools guardedTools(io.mateu.workflow.security.AuthorizationContext caller) {
        var payout = new WorkflowDefinition("payout", "Payout", 1, "", false, 0, false, null, 0, List.of(), false,
                io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE,
                io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE, List.of("payments:write"), List.of(), 0, null);
        when(definitions.findAll()).thenReturn(List.of(payout));
        when(definitions.findById("payout")).thenReturn(java.util.Optional.of(payout));
        var receipts = new InMemoryEventReceiptRepository();
        var pauses = new InMemoryTriggerPauseRepository();
        var service = new EventTriggerService(definitions, pauses, receipts, new EventDestinationsProperties(),
                new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository()).withFlowAuthorization(true);
        var guarded = new EventTriggerMcpTools(service, mock(InboundEventService.class));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        if (caller != null) {
            beans.addBean("caller", (io.mateu.workflow.security.CallerResolver) () -> caller);
        }
        guarded.callerResolver = beans.getBeanProvider(io.mateu.workflow.security.CallerResolver.class);
        return guarded;
    }

    /** With flow authorization on, a trigger starts its workflow as the engine — so its author is judged now. */
    @Test
    void aCallerWhoMayNotStartTheWorkflowCannotCreateOrEditItsTrigger() {
        var allowed = guardedTools(new io.mateu.workflow.security.AuthorizationContext("alice",
                List.of("payments:write"), List.of()));
        assertThat(allowed.createEventTrigger("cdc", "payout", "payments", "*", null, null, null).origin())
                .isEqualTo("API");

        var refused = guardedTools(new io.mateu.workflow.security.AuthorizationContext("bob", List.of(), List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        refused.createEventTrigger("other", "payout", "payments", "*", null, null, null))
                .isInstanceOf(io.mateu.workflow.security.FlowAuthorizationDeniedException.class)
                .hasMessageContaining("payments:write");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guardedTools(null)
                        .createEventTrigger("other", "payout", "payments", "*", null, null, null))
                .isInstanceOf(io.mateu.workflow.security.FlowAuthorizationDeniedException.class);
    }

    @Test
    void anInjectedEventGetsARandomIdAndTheMcpSourceWhenTheAgentGivesNone() {
        var inbound = mock(InboundEventService.class);
        var injecting = new EventTriggerMcpTools(mock(EventTriggerService.class), inbound);

        injecting.publishInboundEvent("bookings", "com.acme.booking.created", "{}", " ", null, "s", "k");

        var event = org.mockito.ArgumentCaptor.forClass(io.mateu.workflow.domain.aggregates.InboundEvent.class);
        verify(inbound).receive(event.capture());
        assertThat(event.getValue().id()).isNotBlank().isNotEqualTo(" ");
        assertThat(event.getValue().source()).isEqualTo("mcp");
        assertThat(event.getValue().subject()).isEqualTo("s");
        assertThat(event.getValue().key()).isEqualTo("k");
        assertThat(injecting.getSystemContext()).contains("createEventTrigger", "publishInboundEvent");
    }

    @Test
    void theReceivedEventsDefaultTo50() {
        var service = mock(EventTriggerService.class);
        new EventTriggerMcpTools(service, mock(InboundEventService.class)).listReceivedEvents(null, null);
        verify(service).received(50, null);
    }
}
