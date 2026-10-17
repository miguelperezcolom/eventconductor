package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.EventWait;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.InMemoryEventReceiptRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The domain-event endpoints through the dispatcher, over the real services and in-memory stores. */
class EventRestControllerMvcTest {

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final IngressRouter ingressRouter = mock(IngressRouter.class);
    final MessageDispatcher messageDispatcher = mock(MessageDispatcher.class);
    final MessageApiProperties apiProperties = new MessageApiProperties();

    {
        // These tests call without a key: an isolated engine that opted into anonymous access (off by default since 2.27.0).
        apiProperties.setAllowAnonymous(true);
    }
    MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var wait = new Step("wait", null, StepType.WAIT_FOR_MESSAGE, "Wait", null, "start", null, null, false, null,
                null, null, null, null, 0, null, "payment-captured", "bookingId", null, 0, 0, false, null, 0, null)
                .withOnEvent(new EventWait("payments", "com.acme.payment.captured", "${data.bookingId}", null, null));
        when(definitions.findAll()).thenReturn(List.of(
                new WorkflowDefinition("onboarding", "Onboarding", 1, null, false, 0, false, null, 0, List.of(wait))
                        .withTriggers(List.of(new EventTrigger(null, "bookings", "com.acme.booking.*", null, null,
                                Map.of("bookingId", "${data.bookingId}"))))));
        var receipts = new InMemoryEventReceiptRepository();
        var pauses = new InMemoryTriggerPauseRepository();
        var inbound = new InboundEventService(definitions, new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository(), receipts, pauses, ingressRouter, messageDispatcher,
                mock(ObjectProvider.class), null);
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) () -> new AuthorizationContext("alice", List.of(), List.of()));
        var controller = new EventRestController(inbound,
                new EventTriggerService(definitions, pauses, receipts, new EventDestinationsProperties(), new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository()), apiProperties,
                beans.getBeanProvider(CallerResolver.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void aTriggerIsCreatedEditedExportedAndDeletedThroughTheApi() throws Exception {
        var onboarding = definitions.findAll().get(0);
        when(definitions.findById("onboarding")).thenReturn(java.util.Optional.of(onboarding));
        mvc.perform(post("/workflow/api/triggers").contentType("application/json").content("""
                        {"id": "cdc", "workflowId": "onboarding", "destination": "customers-cdc", "type": "*",
                         "filter": "data.op == 'u'", "variables": {"customerId": "${data.after.id}"}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/workflow/api/triggers/onboarding/cdc"))
                .andExpect(jsonPath("$.origin").value("API"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.sourceLabel").value("UI/API"));

        mvc.perform(put("/workflow/api/triggers/onboarding/cdc").contentType("application/json").content("""
                        {"destination": "customers-cdc", "type": "io.debezium.*"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("io.debezium.*"))
                .andExpect(jsonPath("$.filter").doesNotExist());

        mvc.perform(get("/workflow/api/triggers/onboarding/cdc/yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("- id: cdc")));

        // A file-owned trigger is not edited here: 409. A bad draft: 400 with the violations.
        mvc.perform(put("/workflow/api/triggers/onboarding/com.acme.booking.any").contentType("application/json")
                        .content("{\"destination\": \"bookings\", \"type\": \"t\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/triggers").contentType("application/json")
                        .content("{\"workflowId\": \"ghost\", \"destination\": \"bad name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(4));

        mvc.perform(delete("/workflow/api/triggers/onboarding/cdc")).andExpect(status().isNoContent());
        mvc.perform(get("/workflow/api/triggers/onboarding/cdc")).andExpect(status().isNotFound());
    }

    private static final String CREATED = """
            {"specversion": "1.0", "id": "e-1", "source": "pms", "type": "com.acme.booking.created",
             "data": {"bookingId": "B-1"}}
            """;

    @Test
    void anInjectedEventStartsTheTriggeredWorkflowOnce() throws Exception {
        mvc.perform(post("/workflow/api/events/bookings").contentType("application/json").content(CREATED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipts[0].outcome").value("STARTED"))
                .andExpect(jsonPath("$.receipts[0].triggerId").value("com.acme.booking.any"));
        mvc.perform(post("/workflow/api/events/bookings").contentType("application/json").content(CREATED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates[0]").value("trigger:onboarding/com.acme.booking.any"));

        var creation = ArgumentCaptor.forClass(ProcessCreationRequested.class);
        verify(ingressRouter).route(creation.capture());
        assertThat(creation.getValue().workflowDefinitionId()).isEqualTo("onboarding");
        assertThat(creation.getValue().caller()).isEqualTo(AuthorizationContext.SYSTEM);
        assertThat(creation.getValue().variables()).extracting(v -> v.name() + "=" + v.value()).containsExactly("bookingId=B-1");

        mvc.perform(get("/workflow/api/events/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("e-1"));
    }

    @Test
    void anInjectedEventCompletesAWaitByItsMessage() throws Exception {
        mvc.perform(post("/workflow/api/events/payments").contentType("application/json")
                        .content("{\"id\": \"p-1\", \"source\": \"pay\", \"type\": \"com.acme.payment.captured\","
                                + " \"data\": {\"bookingId\": \"B-9\", \"amount\": 3}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipts[0].outcome").value("DELIVERED"));

        var message = ArgumentCaptor.forClass(MessageReceived.class);
        verify(messageDispatcher).dispatch(message.capture());
        assertThat(message.getValue().messageName()).isEqualTo("payment-captured");
        assertThat(message.getValue().correlationKey()).isEqualTo("B-9");
        assertThat(message.getValue().variables()).extracting(v -> v.name() + "=" + v.value())
                .containsExactly("bookingId=B-9", "amount=3");
    }

    @Test
    void anEventWithoutIdIsABadRequest() throws Exception {
        mvc.perform(post("/workflow/api/events/bookings").contentType("application/json")
                        .content("{\"source\": \"pms\", \"type\": \"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void triggersAreListedPausedAndResumed() throws Exception {
        mvc.perform(get("/workflow/api/triggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].triggerId").value("com.acme.booking.any"))
                .andExpect(jsonPath("$[0].state").value("ACTIVE"));
        mvc.perform(post("/workflow/api/triggers/onboarding/com.acme.booking.any/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pausedBy").value("alice"))
                .andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post("/workflow/api/triggers/onboarding/com.acme.booking.any/resume"))
                .andExpect(jsonPath("$.state").value("ACTIVE"));
        mvc.perform(get("/workflow/api/triggers/onboarding/nope")).andExpect(status().isNotFound());
    }

    @Test
    void theApiKeyIsRequiredWhenConfigured() throws Exception {
        apiProperties.setApiKey("secret");
        mvc.perform(get("/workflow/api/triggers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/triggers").header("X-Api-Key", "secret")).andExpect(status().isOk());
    }

    @Test
    void readsOneTriggerAndTheLatestReceiptsOfOneWorkflow() throws Exception {
        mvc.perform(get("/workflow/api/triggers/onboarding/com.acme.booking.any"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("onboarding"))
                .andExpect(jsonPath("$.editable").value(false));
        mvc.perform(post("/workflow/api/events/bookings").contentType("application/json").content(CREATED));

        mvc.perform(get("/workflow/api/events/received").param("workflowId", "onboarding").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/workflow/api/events/received").param("workflowId", "someone-else"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aNullBodyIsABadRequest() throws Exception {
        mvc.perform(post("/workflow/api/triggers").contentType("application/json").content("null"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/workflow/api/triggers/onboarding/cdc").contentType("application/json").content("null"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/events/bookings").contentType("application/json").content("null"))
                .andExpect(status().isBadRequest());
    }

    /** The path names the workflow; a body naming another one is refused, not a silent move. */
    @Test
    void anEditCannotMoveATriggerToAnotherWorkflow() throws Exception {
        var onboarding = definitions.findAll().get(0);
        when(definitions.findById("onboarding")).thenReturn(java.util.Optional.of(onboarding));
        mvc.perform(post("/workflow/api/triggers").contentType("application/json").content("""
                        {"id": "cdc", "workflowId": "onboarding", "destination": "customers-cdc", "type": "*"}
                        """))
                .andExpect(status().isCreated());

        mvc.perform(put("/workflow/api/triggers/onboarding/cdc").contentType("application/json").content("""
                        {"workflowId": "billing", "destination": "customers-cdc", "type": "*"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0]", org.hamcrest.Matchers.containsString("never changes")));
        mvc.perform(put("/workflow/api/triggers/onboarding/ghost").contentType("application/json")
                        .content("{\"destination\": \"customers-cdc\", \"type\": \"*\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/workflow/api/triggers/onboarding/com.acme.booking.any")).andExpect(status().isConflict());
    }

    /** An identifier over the engine's limit is refused here, naming the field, before anything is received. */
    @Test
    void anOversizedIdentifierIsABadRequestThatSaysWhich() throws Exception {
        var huge = "x".repeat(io.mateu.workflow.input.InputLimits.MAX_IDENTIFIER_LENGTH + 1);
        mvc.perform(post("/workflow/api/events/bookings").contentType("application/json")
                        .content("{\"id\": \"" + huge + "\", \"source\": \"pms\", \"type\": \"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(org.hamcrest.Matchers.containsString("id")));
        mvc.perform(post("/workflow/api/events/" + huge).contentType("application/json").content(CREATED))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(org.hamcrest.Matchers.containsString("destination")));
        org.mockito.Mockito.verifyNoInteractions(ingressRouter);
    }

    @Test
    void aCallerWhoMayNotStartTheWorkflowCannotCreateItsTrigger_403() throws Exception {
        var payout = new WorkflowDefinition("payout", "Payout", 1, "", false, 0, false, null, 0, List.of(), false,
                io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE,
                io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE, List.of("payments:write"), List.of(), 0, null);
        when(definitions.findAll()).thenReturn(List.of(payout));
        when(definitions.findById("payout")).thenReturn(java.util.Optional.of(payout));
        var receipts = new InMemoryEventReceiptRepository();
        var pauses = new InMemoryTriggerPauseRepository();
        var triggers = new EventTriggerService(definitions, pauses, receipts, new EventDestinationsProperties(),
                new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository()).withFlowAuthorization(true);
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) () -> new AuthorizationContext("bob", List.of(), List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new EventRestController(mock(InboundEventService.class), triggers,
                apiProperties, beans.getBeanProvider(CallerResolver.class))).build();

        mvc.perform(post("/workflow/api/triggers").contentType("application/json").content("""
                        {"id": "cdc", "workflowId": "payout", "destination": "payments", "type": "*"}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("payments:write")));
        mvc.perform(get("/workflow/api/triggers").param("workflowId", "payout"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void withoutACallerResolverThePauseIsTheApis() throws Exception {
        var receipts = new InMemoryEventReceiptRepository();
        var triggers = new EventTriggerService(definitions, new InMemoryTriggerPauseRepository(), receipts,
                new EventDestinationsProperties(), new io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository());
        mvc = MockMvcBuilders.standaloneSetup(new EventRestController(mock(InboundEventService.class), triggers,
                apiProperties, new StaticListableBeanFactory().getBeanProvider(CallerResolver.class))).build();

        mvc.perform(post("/workflow/api/triggers/onboarding/com.acme.booking.any/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pausedBy").value("api"));
    }

    @Test
    void aWrongApiKeyIsRefusedOnEveryEndpoint() throws Exception {
        apiProperties.setApiKey("secret");
        var wrong = "Secret";

        mvc.perform(post("/workflow/api/events/bookings").header("X-Api-Key", wrong).contentType("application/json")
                .content(CREATED)).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/events/received").header("X-Api-Key", wrong)).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/triggers/onboarding/com.acme.booking.any")).andExpect(status().isUnauthorized());
        mvc.perform(post("/workflow/api/triggers").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/workflow/api/triggers/onboarding/x").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/workflow/api/triggers/onboarding/x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/triggers/onboarding/x/yaml")).andExpect(status().isUnauthorized());
        mvc.perform(post("/workflow/api/triggers/onboarding/com.acme.booking.any/pause")).andExpect(status().isUnauthorized());
        mvc.perform(post("/workflow/api/triggers/onboarding/com.acme.booking.any/resume")).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(ingressRouter);
    }
}
