package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.SyncInvocationController;
import io.mateu.workflow.security.CallerResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The synchronous invocation endpoint driven through Spring MVC — dispatcher, async request,
 * status mapping, JSON — over the real engine the e2e context booted, without a servlet container.
 */
public class SyncInvocationHttp {

    private final MockMvc mvc;

    public SyncInvocationHttp(SyncInvocationService service, SyncReplyWaiters waiters, WorkflowMetrics metrics) {
        var controller = new SyncInvocationController(service, waiters, new MessageApiProperties(),
                new StaticListableBeanFactory().getBeanProvider(CallerResolver.class), metrics);
        controller.init();
        this.mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** POSTs an invocation and returns the final response (after the async wait). */
    public MvcResult invoke(String definitionId, String idempotencyKey, String prefer, String body) throws Exception {
        var request = post("/workflow/api/definitions/" + definitionId + "/invocations")
                .contentType("application/json")
                .content(body == null ? "{}" : body);
        if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
        if (prefer != null) request = request.header("Prefer", prefer);
        return finish(mvc.perform(request).andReturn());
    }

    public MvcResult get(String path, String prefer) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        if (prefer != null) request = request.header("Prefer", prefer);
        return finish(mvc.perform(request).andReturn());
    }

    private MvcResult finish(MvcResult first) throws Exception {
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(60_000);
            return mvc.perform(asyncDispatch(first)).andReturn();
        }
        return first;
    }
}
