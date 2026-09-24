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
}
