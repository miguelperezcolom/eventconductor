package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.Invocation;
import io.mateu.workflow.application.sync.InvocationProgress;
import io.mateu.workflow.application.sync.SyncInvocationRejectedException;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The invocation endpoints through the dispatcher, over a stubbed service: statuses, headers, refusals. */
class SyncInvocationControllerMvcTest {

    static final Invocation INVOCATION = new Invocation("inv-1", "wd", "k", "h", "p-1", null,
            LocalDateTime.now(), LocalDateTime.now().plusDays(1), null);

    SyncInvocationService service = mock(SyncInvocationService.class);
    SyncReplyWaiters waiters = mock(SyncReplyWaiters.class);
    MessageApiProperties apiProperties = new MessageApiProperties();

    {
        // These tests call without a key: an isolated engine that opted into anonymous access (off by default since 2.27.0).
        apiProperties.setAllowAnonymous(true);
    }
    SyncInvocationController controller;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new SyncInvocationController(service, waiters, apiProperties,
                new StaticListableBeanFactory().getBeanProvider(CallerResolver.class), WorkflowMetrics.NOOP);
        controller.streamIntervalMs = 10;
        controller.init();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.waitFor(any())).thenAnswer(i -> i.getArgument(0) == null ? Duration.ZERO : i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        controller.shutdown();
    }

    private void startsAndAnswers(ProcessReply reply) {
        when(service.start(any())).thenReturn(new SyncInvocationService.Started(INVOCATION, true, Duration.ofSeconds(1)));
        when(service.await(eq(INVOCATION), any())).thenReturn(CompletableFuture.completedFuture(
                new SyncInvocationService.View(INVOCATION, reply, ProcessStatus.COMPLETED)));
        when(service.view(INVOCATION)).thenReturn(new SyncInvocationService.View(INVOCATION, reply, ProcessStatus.COMPLETED));
    }

    private MvcResult post(String key, String prefer) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").contentType("application/json")
                .content("{\"businessKey\": \"bk\", \"variables\": {\"a\": \"1\", \"n\": 2, \"o\": {\"x\": true}, \"z\": null}}");
        if (key != null) request = request.header("Idempotency-Key", key);
        if (prefer != null) request = request.header("Prefer", prefer);
        var first = mvc.perform(request).andReturn();
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(5_000);
            return mvc.perform(asyncDispatch(first)).andReturn();
        }
        return first;
    }

    @Test
    void aReplyIs200WithThePayloadVerbatim() throws Exception {
        startsAndAnswers(ProcessReply.replied("r", "{\"ok\":true}"));
        var result = post("k", "wait=3");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"reply\":{\"ok\":true}").contains("\"outcome\":\"REPLIED\"");
    }

    @Test
    void aFailureIs502AndNoReplyYetIs202WithALocation() throws Exception {
        startsAndAnswers(ProcessReply.of(ProcessReply.Outcome.FAILED, ProcessReply.Compensation.IN_PROGRESS, "boom"));
        assertThat(post("k", null).getResponse().getStatus()).isEqualTo(502);

        startsAndAnswers(null);
        var pending = post("k", "wait=0");
        assertThat(pending.getResponse().getStatus()).isEqualTo(202);
        assertThat(pending.getResponse().getHeader("Location")).isEqualTo("/workflow/api/invocations/inv-1");
    }

    @Test
    void theRefusalsMapToTheirStatuses() throws Exception {
        assertThat(post(null, null).getResponse().getStatus()).isEqualTo(400);
        for (var pair : List.of(
                List.of(SyncInvocationRejectedException.Reason.NOT_SYNC_INVOCABLE, 409),
                List.of(SyncInvocationRejectedException.Reason.LOCK_BUSY, 409),
                List.of(SyncInvocationRejectedException.Reason.IDEMPOTENCY_KEY_REUSED, 422),
                List.of(SyncInvocationRejectedException.Reason.OVERLOADED, 429))) {
            org.mockito.Mockito.doThrow(new SyncInvocationRejectedException(
                    (SyncInvocationRejectedException.Reason) pair.get(0), "no")).when(service).start(any());
            assertThat(post("k", null).getResponse().getStatus()).as(pair.get(0).toString()).isEqualTo(pair.get(1));
        }
        org.mockito.Mockito.doThrow(new UnknownWorkflowDefinitionException("wd")).when(service).start(any());
        assertThat(post("k", null).getResponse().getStatus()).isEqualTo(404);
        org.mockito.Mockito.doThrow(FlowAuthorizationDeniedException.of("start", "me", List.of("s"), List.of()))
                .when(service).start(any());
        assertThat(post("k", null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anApiKeyWhenConfiguredIsRequired() throws Exception {
        apiProperties.setApiKey("secret");
        startsAndAnswers(ProcessReply.replied("r", "{}"));
        assertThat(post("k", null).getResponse().getStatus()).isEqualTo(401);
        var ok = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").contentType("application/json").content("{}")
                .header("Idempotency-Key", "k").header("X-Api-Key", "secret")).andReturn();
        ok.getAsyncResult(5_000);
        assertThat(mvc.perform(asyncDispatch(ok)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void readsById_andByKey_andSays404WhenUnknown() throws Exception {
        when(service.find("inv-1")).thenReturn(Optional.of(INVOCATION));
        when(service.find("nope")).thenReturn(Optional.empty());
        when(service.findByKey("wd", "k")).thenReturn(Optional.of(INVOCATION));
        when(service.view(INVOCATION)).thenReturn(new SyncInvocationService.View(INVOCATION,
                ProcessReply.replied("r", "{}"), ProcessStatus.COMPLETED));
        when(service.await(eq(INVOCATION), any())).thenReturn(CompletableFuture.completedFuture(
                new SyncInvocationService.View(INVOCATION, ProcessReply.replied("r", "{}"), ProcessStatus.COMPLETED)));

        var byId = mvc.perform(get("/workflow/api/invocations/inv-1")).andReturn();
        assertThat(byId.getAsyncResult(5_000)).isNotNull();
        assertThat(mvc.perform(asyncDispatch(byId)).andReturn().getResponse().getStatus()).isEqualTo(200);

        var waiting = mvc.perform(get("/workflow/api/invocations/inv-1").header("Prefer", "wait=2")).andReturn();
        waiting.getAsyncResult(5_000);
        assertThat(mvc.perform(asyncDispatch(waiting)).andReturn().getResponse().getStatus()).isEqualTo(200);

        var byKey = mvc.perform(get("/workflow/api/definitions/wd/invocations").param("idempotencyKey", "k")).andReturn();
        byKey.getAsyncResult(5_000);
        assertThat(mvc.perform(asyncDispatch(byKey)).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(mvc.perform(get("/workflow/api/invocations/nope")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void streamsProgressAndEndsWithTheReply() throws Exception {
        when(service.start(any())).thenReturn(new SyncInvocationService.Started(INVOCATION, true, Duration.ofSeconds(2)));
        var replied = new SyncInvocationService.View(INVOCATION, ProcessReply.replied("r", "{\"ok\":1}"), ProcessStatus.COMPLETED);
        when(service.tick(eq(INVOCATION), any())).thenAnswer(i -> {
            InvocationProgress progress = i.getArgument(1);
            return new SyncInvocationService.Tick(List.of(progress.now("status", java.util.Map.of("key", "status:RUNNING"))),
                    replied, true);
        });
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").accept("text/event-stream")
                .contentType("application/json").content("{}").header("Idempotency-Key", "k")).andReturn();
        result.getAsyncResult(5_000);
        var body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:status").contains("event:reply").contains("\"ok\":1");
    }

    @Test
    void aStreamWhoseDeadlinePassesEndsWithATimeoutEvent() throws Exception {
        when(service.find("inv-1")).thenReturn(Optional.of(INVOCATION));
        when(service.tick(eq(INVOCATION), any())).thenReturn(new SyncInvocationService.Tick(List.of(),
                new SyncInvocationService.View(INVOCATION, null, ProcessStatus.RUNNING), false));
        var result = mvc.perform(get("/workflow/api/invocations/inv-1").accept("text/event-stream")
                .header("Prefer", "wait=1")).andReturn();
        result.getAsyncResult(5_000);
        assertThat(result.getResponse().getContentAsString()).contains("event:timeout").contains("/workflow/api/invocations/inv-1");
    }

    // ── Admission, input limits, the caller ──────────────────────────────────────────────────────

    private SyncInvocationController withRoomFor(int waiting, CallerResolver resolver) {
        controller.shutdown();
        var beans = new StaticListableBeanFactory();
        if (resolver != null) {
            beans.addBean("caller", resolver);
        }
        controller = new SyncInvocationController(service, waiters, apiProperties,
                beans.getBeanProvider(CallerResolver.class), WorkflowMetrics.NOOP);
        controller.maxWaiting = waiting;
        controller.streamIntervalMs = 10;
        controller.init();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        return controller;
    }

    /** Past workflow.sync.max-waiting callers on this node, the next one is told to come back: 429. */
    @Test
    void aNodeWithEveryWaitingSlotTakenAnswers429WithRetryAfter() throws Exception {
        withRoomFor(1, null);
        var never = new CompletableFuture<SyncInvocationService.View>();
        when(service.start(any())).thenReturn(new SyncInvocationService.Started(INVOCATION, true, Duration.ofSeconds(30)));
        when(service.await(eq(INVOCATION), any())).thenReturn(never);
        when(service.view(INVOCATION)).thenReturn(new SyncInvocationService.View(INVOCATION,
                ProcessReply.replied("r", "{}"), ProcessStatus.COMPLETED));

        var waiting = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").contentType("application/json").content("{}")
                .header("Idempotency-Key", "k1")).andReturn();
        assertThat(waiting.getRequest().isAsyncStarted()).isTrue();

        var refused = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").contentType("application/json").content("{}")
                .header("Idempotency-Key", "k2")).andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("1");
        assertThat(refused.getContentAsString()).contains("\"reason\":\"OVERLOADED\"");

        // A read that does not wait takes no slot.
        when(service.find("inv-1")).thenReturn(Optional.of(INVOCATION));
        var read = mvc.perform(get("/workflow/api/invocations/inv-1")).andReturn();
        read.getAsyncResult(5_000);
        assertThat(mvc.perform(asyncDispatch(read)).andReturn().getResponse().getStatus()).isEqualTo(200);

        never.complete(new SyncInvocationService.View(INVOCATION, ProcessReply.replied("r", "{}"), ProcessStatus.COMPLETED));
        waiting.getAsyncResult(5_000);
        assertThat(mvc.perform(asyncDispatch(waiting)).andReturn().getResponse().getStatus()).isEqualTo(200);

        // Answered, the caller's slot is free again.
        when(service.await(eq(INVOCATION), any())).thenReturn(CompletableFuture.completedFuture(
                new SyncInvocationService.View(INVOCATION, ProcessReply.replied("r", "{}"), ProcessStatus.COMPLETED)));
        assertThat(post("k3", null).getResponse().getStatus()).isEqualTo(200);
    }

    /** A start that is refused gives its slot back: refusals never fill the node up. */
    @Test
    void aRefusedStartGivesItsWaitingSlotBack() throws Exception {
        withRoomFor(1, null);
        org.mockito.Mockito.doThrow(new SyncInvocationRejectedException(
                SyncInvocationRejectedException.Reason.NOT_ACCEPTING, "disabled")).when(service).start(any());

        for (int i = 0; i < 3; i++) {
            assertThat(post("k", null).getResponse().getStatus()).as("attempt %s", i).isEqualTo(409);
        }
        for (int i = 0; i < 3; i++) {
            var stream = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/workflow/api/definitions/wd/invocations").accept("text/event-stream")
                    .contentType("application/json").content("{}").header("Idempotency-Key", "k")).andReturn();
            assertThat(stream.getResponse().getStatus()).as("stream attempt %s", i).isEqualTo(409);
        }
    }

    @Test
    void aRefusalCarriesItsReason_theShardThatOwnsTheProcess_andRetryAfterWhenALockIsBusy() throws Exception {
        org.mockito.Mockito.doThrow(new SyncInvocationRejectedException(
                SyncInvocationRejectedException.Reason.ON_ANOTHER_SHARD, "owned elsewhere", "shard-2"))
                .when(service).start(any());
        var elsewhere = post("k", null).getResponse();
        assertThat(elsewhere.getStatus()).isEqualTo(409);
        assertThat(elsewhere.getContentAsString()).contains("\"reason\":\"ON_ANOTHER_SHARD\"", "\"shard\":\"shard-2\"");
        assertThat(elsewhere.getHeader("Retry-After")).isNull();

        org.mockito.Mockito.doThrow(new SyncInvocationRejectedException(
                SyncInvocationRejectedException.Reason.LOCK_BUSY, "busy")).when(service).start(any());
        var busy = post("k", null).getResponse();
        assertThat(busy.getHeader("Retry-After")).isEqualTo("1");
        assertThat(busy.getContentAsString()).doesNotContain("shard");

        org.mockito.Mockito.doThrow(new SyncInvocationRejectedException(
                SyncInvocationRejectedException.Reason.BUSINESS_KEY_TAKEN, "taken")).when(service).start(any());
        assertThat(post("k", null).getResponse().getStatus()).isEqualTo(409);
    }

    /** Refused on the request, naming the field — not dead-lettered once the process exists. */
    @Test
    void anOversizedKeyOrVariableIsABadRequestAndStartsNothing() throws Exception {
        var huge = "x".repeat(io.mateu.workflow.input.InputLimits.MAX_IDENTIFIER_LENGTH + 1);
        var badKey = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").contentType("application/json").content("{}")
                .header("Idempotency-Key", huge)).andReturn().getResponse();
        assertThat(badKey.getStatus()).isEqualTo(400);
        assertThat(badKey.getErrorMessage()).contains("Idempotency-Key");

        var badBusinessKey = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").contentType("application/json")
                .content("{\"businessKey\": \"" + huge + "\"}").header("Idempotency-Key", "k")).andReturn().getResponse();
        assertThat(badBusinessKey.getStatus()).isEqualTo(400);
        assertThat(badBusinessKey.getErrorMessage()).contains("businessKey");

        var bigValue = "y".repeat(io.mateu.workflow.input.InputLimits.MAX_VALUE_LENGTH + 1);
        var badVariable = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").accept("text/event-stream").contentType("application/json")
                .content("{\"variables\": {\"v\": \"" + bigValue + "\"}}").header("Idempotency-Key", "k")).andReturn().getResponse();
        assertThat(badVariable.getStatus()).isEqualTo(400);

        var streamWithoutKey = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").accept("text/event-stream")
                .contentType("application/json").content("{}")).andReturn().getResponse();
        assertThat(streamWithoutKey.getStatus()).isEqualTo(400);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).start(any());
    }

    /**
     * The request handed to the service: the resolved caller (what flow authorization judges), the
     * requested wait, and the variables — a string as itself, anything else as its JSON.
     */
    @Test
    void theServiceIsAskedWithTheResolvedCallerTheWaitAndTheVariablesAsText() throws Exception {
        var alice = new io.mateu.workflow.security.AuthorizationContext("alice", List.of("orders:write"), List.of());
        withRoomFor(10, () -> alice);
        startsAndAnswers(ProcessReply.replied("r", "{}"));

        post("k", "respond-async, wait=4");

        var request = org.mockito.ArgumentCaptor.forClass(SyncInvocationService.StartRequest.class);
        org.mockito.Mockito.verify(service).start(request.capture());
        assertThat(request.getValue().caller()).isEqualTo(alice);
        assertThat(request.getValue().workflowDefinitionId()).isEqualTo("wd");
        assertThat(request.getValue().idempotencyKey()).isEqualTo("k");
        assertThat(request.getValue().businessKey()).isEqualTo("bk");
        assertThat(request.getValue().requestedWait()).isEqualTo(Duration.ofSeconds(4));
        assertThat(request.getValue().variables()).containsExactly(
                java.util.Map.entry("a", "1"), java.util.Map.entry("n", "2"),
                java.util.Map.entry("o", "{\"x\":true}"), new java.util.AbstractMap.SimpleEntry<>("z", null));
    }

    @Test
    void anUnknownInvocationOrKeyIs404_alsoAsAStream() throws Exception {
        when(service.find("nope")).thenReturn(Optional.empty());
        when(service.findByKey("wd", "nope")).thenReturn(Optional.empty());

        assertThat(mvc.perform(get("/workflow/api/invocations/nope").accept("text/event-stream"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/workflow/api/definitions/wd/invocations").param("idempotencyKey", "nope"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theApiKeyGuardsTheReadsAndTheStreamsToo() throws Exception {
        apiProperties.setApiKey("secret");
        when(service.find("inv-1")).thenReturn(Optional.of(INVOCATION));

        assertThat(mvc.perform(get("/workflow/api/invocations/inv-1").header("X-Api-Key", "wrong"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/workflow/api/invocations/inv-1").accept("text/event-stream"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/workflow/api/definitions/wd/invocations").param("idempotencyKey", "k"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/workflow/api/definitions/wd/invocations").accept("text/event-stream")
                .contentType("application/json").content("{}").header("Idempotency-Key", "k"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).start(any());
    }

    /** follow=true: the reply is sent once and the stream stays open until the process finishes. */
    @Test
    void aFollowedStreamSendsTheReplyOnceAndEndsWhenTheProcessFinishes() throws Exception {
        when(service.find("inv-1")).thenReturn(Optional.of(INVOCATION));
        var ticks = new java.util.concurrent.atomic.AtomicInteger();
        var replied = new SyncInvocationService.View(INVOCATION, ProcessReply.replied("r", "{\"ok\":1}"), ProcessStatus.RUNNING);
        when(service.tick(eq(INVOCATION), any())).thenAnswer(i ->
                new SyncInvocationService.Tick(List.of(), replied, ticks.incrementAndGet() >= 4));

        var result = mvc.perform(get("/workflow/api/invocations/inv-1").accept("text/event-stream")
                .param("follow", "true").header("Prefer", "wait=5")).andReturn();
        result.getAsyncResult(5_000);

        var body = result.getResponse().getContentAsString();
        assertThat(ticks.get()).isGreaterThanOrEqualTo(4);
        assertThat(body.split("event:reply", -1)).hasSize(2);
        assertThat(body).doesNotContain("event:timeout");
    }

    /**
     * A stream whose look at the process fails is ended — whatever the exception type — and gives its
     * slot back. An IllegalStateException from the service used to be taken for "the caller hung up":
     * the slot was released but the stream was left open, silent, until its servlet timeout.
     */
    @Test
    void aStreamWhoseLookFailsIsEndedAndGivesItsSlotBack() throws Exception {
        withRoomFor(1, null);
        when(service.find("inv-1")).thenReturn(Optional.of(INVOCATION));
        when(service.tick(eq(INVOCATION), any())).thenThrow(new IllegalStateException("definition gone"))
                .thenThrow(new RuntimeException("db down"));

        for (var failure : List.of("definition gone", "db down")) {
            var result = mvc.perform(get("/workflow/api/invocations/inv-1").accept("text/event-stream")
                    .header("Prefer", "wait=60")).andReturn();
            // The first look runs on the stream scheduler at once, so it can fail and end the emitter
            // before the request has gone async; either way the failure ends the stream. When it went
            // async, the error is the stream's result.
            if (result.getRequest().isAsyncStarted()) {
                assertThat(result.getAsyncResult(2_000)).as(failure)
                        .isInstanceOfSatisfying(Throwable.class, e -> assertThat(e).hasMessage(failure));
            }
        }
        // The slot came back both times: a third caller is still admitted, not refused 429.
        when(service.tick(eq(INVOCATION), any())).thenReturn(new SyncInvocationService.Tick(List.of(),
                new SyncInvocationService.View(INVOCATION, ProcessReply.replied("r", "{}"), ProcessStatus.COMPLETED), true));
        // A failed stream gives its slot back from the thread that ended it, which can run just after
        // the request above returned: ask again until the caller is admitted. A refusal takes no
        // slot, and a slot that never came back still fails here after two seconds.
        var admitted = new java.util.concurrent.atomic.AtomicReference<org.springframework.test.web.servlet.MvcResult>();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                .pollInterval(java.time.Duration.ofMillis(20))
                .until(() -> {
                    var attempt = mvc.perform(get("/workflow/api/invocations/inv-1").accept("text/event-stream")).andReturn();
                    admitted.set(attempt);
                    return attempt.getResponse().getStatus() != 429;
                });
        var third = admitted.get();
        assertThat(third.getResponse().getStatus()).isNotEqualTo(429);
        third.getAsyncResult(2_000);
        assertThat(third.getResponse().getContentAsString()).contains("event:reply");
    }
}
