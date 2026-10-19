package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateRejectedException;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The update endpoints through the dispatcher, over a stubbed service: statuses, headers, problems. */
class ProcessUpdateControllerMvcTest {

    final ProcessUpdateService service = mock(ProcessUpdateService.class);
    final MessageApiProperties apiProperties = new MessageApiProperties();
    ProcessUpdateController controller;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        apiProperties.setApiKey("k");
        controller = new ProcessUpdateController(service, apiProperties,
                new StaticListableBeanFactory().getBeanProvider(CallerResolver.class));
        controller.init();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.waitFor(any())).thenAnswer(i -> i.getArgument(0) == null ? Duration.ZERO : i.getArgument(0));
    }

    static ProcessUpdate update(ProcessUpdate.Status status, String result, String error) {
        return new ProcessUpdate("u-1", "p-1", "orders", "rename", "key", "h", "{\"name\":\"****\"}", status, result,
                error, "ana", false, LocalDateTime.of(2026, 10, 19, 9, 0), null, null);
    }

    void submitsAndAnswers(ProcessUpdate submitted, ProcessUpdate outcome) {
        when(service.submit(any())).thenReturn(new ProcessUpdateService.Submitted(submitted, true, Duration.ofSeconds(1)));
        when(service.await(eq(submitted), any())).thenReturn(CompletableFuture.completedFuture(outcome));
    }

    MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult first = mvc.perform(request.header("X-Api-Key", "k")).andReturn();
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(10_000);
            return mvc.perform(asyncDispatch(first)).andReturn().getResponse();
        }
        return first.getResponse();
    }

    MockHttpServletResponse postUpdate(String body) throws Exception {
        return perform(post("/workflow/api/processes/p-1/updates/rename").contentType("application/json")
                .header("Idempotency-Key", "key").header("Prefer", "wait=3").content(body));
    }

    @Test
    void anAppliedUpdateIs200WithItsResultVerbatim() throws Exception {
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        submitsAndAnswers(requested, update(ProcessUpdate.Status.COMPLETED, "{\"customer\":\"Ana\"}", null));

        var response = postUpdate("{\"name\": \"Ana\"}");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"result\":{\"customer\":\"Ana\"}")
                .contains("\"payload\":{\"name\":\"****\"}").contains("\"status\":\"COMPLETED\"")
                .contains("\"location\":\"/workflow/api/updates/u-1\"");
        var request = ArgumentCaptor.forClass(ProcessUpdateService.UpdateRequest.class);
        verify(service).submit(request.capture());
        assertThat(request.getValue().payload()).isEqualTo("{\"name\": \"Ana\"}");
        assertThat(request.getValue().idempotencyKey()).isEqualTo("key");
        assertThat(request.getValue().requestedWait()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theDeadlineIs202WithALocation() throws Exception {
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        submitsAndAnswers(requested, requested);

        var response = postUpdate("{}");

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeader("Location")).isEqualTo("/workflow/api/updates/u-1");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void recordedRefusalsAreProblems() throws Exception {
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        for (var outcome : List.of(
                new Object[]{ProcessUpdate.Status.REFUSED, 422, "REFUSED"},
                new Object[]{ProcessUpdate.Status.NOT_ACCEPTED, 409, "NOT_ACCEPTING"},
                new Object[]{ProcessUpdate.Status.FAILED, 500, "FAILED"})) {
            submitsAndAnswers(requested, update((ProcessUpdate.Status) outcome[0], null, "why"));

            var response = postUpdate("{}");

            assertThat(response.getStatus()).isEqualTo(outcome[1]);
            assertThat(response.getContentType()).isEqualTo("application/problem+json");
            assertThat(response.getContentAsString()).contains("\"reason\":\"" + outcome[2] + "\"")
                    .contains("\"detail\":\"why\"").contains("\"updateId\":\"u-1\"");
        }
    }

    @Test
    void refusalsAtTheDoorMapToTheirStatuses() throws Exception {
        for (var reason : ProcessUpdateRejectedException.Reason.values()) {
            org.mockito.Mockito.doThrow(new ProcessUpdateRejectedException(reason, "no",
                    update(ProcessUpdate.Status.REQUESTED, null, null))).when(service).submit(any());

            var response = postUpdate("{}");

            assertThat(response.getStatus()).isEqualTo(ProcessUpdateController.statusOf(reason).value());
            assertThat(response.getContentAsString()).contains("\"reason\":\"" + reason.name() + "\"");
        }
        assertThat(ProcessUpdateController.statusOf(ProcessUpdateRejectedException.Reason.REFUSED).value()).isEqualTo(422);
        assertThat(ProcessUpdateController.statusOf(ProcessUpdateRejectedException.Reason.OVERLOADED).value()).isEqualTo(429);
    }

    @Test
    void otherRefusals() throws Exception {
        org.mockito.Mockito.doThrow(FlowAuthorizationDeniedException.of("update", "eve", List.of(), List.of("clerk"))).when(service).submit(any());
        assertThat(postUpdate("{}").getStatus()).isEqualTo(403);

        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad")).when(service).submit(any());
        assertThat(postUpdate("{}").getStatus()).isEqualTo(400);

        assertThat(mvc.perform(post("/workflow/api/processes/p-1/updates/rename")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void tooManyWaitersAre429() throws Exception {
        controller.maxWaiting = 1;
        controller.init();
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        when(service.submit(any())).thenReturn(new ProcessUpdateService.Submitted(requested, true, Duration.ofSeconds(5)));
        when(service.await(eq(requested), any())).thenReturn(new CompletableFuture<>());
        mvc.perform(post("/workflow/api/processes/p-1/updates/rename").header("X-Api-Key", "k").content("{}")).andReturn();

        var second = perform(post("/workflow/api/processes/p-1/updates/rename").content("{}"));

        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void byBusinessKey() throws Exception {
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        submitsAndAnswers(requested, update(ProcessUpdate.Status.COMPLETED, "1", null));

        var response = perform(post("/workflow/api/processes/by-key/updates/rename").param("businessKey", "order-1")
                .contentType("application/json").content(" "));

        assertThat(response.getStatus()).isEqualTo(200);
        var request = ArgumentCaptor.forClass(ProcessUpdateService.UpdateRequest.class);
        verify(service).submit(request.capture());
        assertThat(request.getValue().businessKey()).isEqualTo("order-1");
        assertThat(request.getValue().processId()).isNull();
        assertThat(request.getValue().payload()).isNull();
    }

    @Test
    void updateWithStart() throws Exception {
        var started = new ProcessUpdate("u-2", "p-2", "orders", "rename", "u-2", "h", null, ProcessUpdate.Status.COMPLETED,
                "{}", null, null, true, LocalDateTime.now(), LocalDateTime.now(), null);
        when(service.updateWithStart(any())).thenReturn(new ProcessUpdateService.StartSubmitted(started, true, Duration.ofSeconds(1)));
        when(service.await(eq(started), any())).thenReturn(CompletableFuture.completedFuture(started));

        var response = perform(post("/workflow/api/definitions/orders/update-with-start").contentType("application/json")
                .content("{\"businessKey\": \"k\", \"variables\": {\"a\": \"x\", \"n\": 2, \"o\": {\"b\": true}},"
                        + " \"update\": \"rename\", \"payload\": {\"name\": \"Ana\"}}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"started\":true");
        var request = ArgumentCaptor.forClass(ProcessUpdateService.StartRequest.class);
        verify(service).updateWithStart(request.capture());
        assertThat(request.getValue().variables()).containsEntry("a", "x").containsEntry("n", "2")
                .containsEntry("o", "{\"b\":true}");
        assertThat(request.getValue().payload()).isEqualTo("{\"name\":\"Ana\"}");

        assertThat(perform(post("/workflow/api/definitions/orders/update-with-start").contentType("application/json"))
                .getStatus()).isEqualTo(400);
        org.mockito.Mockito.doThrow(new ProcessUpdateRejectedException(
                ProcessUpdateRejectedException.Reason.BUSINESS_KEY_TAKEN, "theirs")).when(service).updateWithStart(any());
        assertThat(perform(post("/workflow/api/definitions/orders/update-with-start").contentType("application/json")
                .content("{\"businessKey\": \"k\", \"update\": \"rename\"}")).getStatus()).isEqualTo(409);
    }

    @Test
    void readsAndLongPolls() throws Exception {
        var requested = update(ProcessUpdate.Status.REQUESTED, null, null);
        var done = update(ProcessUpdate.Status.COMPLETED, "true", null);
        when(service.find(eq("u-1"), any())).thenReturn(Optional.of(requested));
        when(service.find(eq("nope"), any())).thenReturn(Optional.empty());
        when(service.await(eq(requested), any())).thenReturn(CompletableFuture.completedFuture(done));
        when(service.list(eq("p-1"), any())).thenReturn(List.of(done));

        assertThat(perform(get("/workflow/api/updates/u-1")).getStatus()).isEqualTo(202);
        assertThat(perform(get("/workflow/api/updates/u-1").header("Prefer", "wait=5")).getStatus()).isEqualTo(200);
        assertThat(perform(get("/workflow/api/updates/nope")).getStatus()).isEqualTo(404);
        var listed = perform(get("/workflow/api/processes/p-1/updates"));
        assertThat(listed.getStatus()).isEqualTo(200);
        assertThat(listed.getContentAsString()).startsWith("[").contains("\"name\":\"rename\"");
    }
}
