package io.mateu.workflow.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.ProcessUpdateController;
import io.mateu.workflow.security.CallerResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * The process-update endpoints driven through Spring MVC — dispatcher, async wait, status mapping,
 * problem+json — over the real engine the e2e context booted, without a servlet container.
 */
public class ProcessUpdatesHttp {

    public static final String API_KEY = "e2e-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc;

    public ProcessUpdatesHttp(ProcessUpdateService service) {
        var apiProperties = new MessageApiProperties();
        apiProperties.setApiKey(API_KEY);
        var controller = new ProcessUpdateController(service, apiProperties,
                new StaticListableBeanFactory().getBeanProvider(CallerResolver.class));
        controller.init();
        this.mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** POSTs an update to a process by id; returns the final response (after the async wait). */
    public MockHttpServletResponse update(String processId, String name, String payload, String idempotencyKey,
                                          String prefer) throws Exception {
        return send(MockMvcRequestBuilders.post("/workflow/api/processes/" + processId + "/updates/" + name),
                payload, idempotencyKey, prefer);
    }

    public MockHttpServletResponse update(String processId, String name, String payload) throws Exception {
        return update(processId, name, payload, null, "wait=10");
    }

    public MockHttpServletResponse updateByKey(String businessKey, String name, String payload) throws Exception {
        return send(MockMvcRequestBuilders.post("/workflow/api/processes/by-key/updates/" + name)
                .param("businessKey", businessKey), payload, null, "wait=10");
    }

    public MockHttpServletResponse updateWithStart(String definitionId, String body, String idempotencyKey)
            throws Exception {
        return send(MockMvcRequestBuilders.post("/workflow/api/definitions/" + definitionId + "/update-with-start"),
                body, idempotencyKey, "wait=10");
    }

    public MockHttpServletResponse get(String path, String prefer) throws Exception {
        var request = MockMvcRequestBuilders.get(path).header("X-Api-Key", API_KEY);
        if (prefer != null) {
            request = request.header("Prefer", prefer);
        }
        return finish(mvc.perform(request).andReturn()).getResponse();
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String body, String idempotencyKey,
                                         String prefer) throws Exception {
        request = request.header("X-Api-Key", API_KEY).contentType("application/json");
        if (body != null) {
            request = request.content(body);
        }
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        if (prefer != null) {
            request = request.header("Prefer", prefer);
        }
        return finish(mvc.perform(request).andReturn()).getResponse();
    }

    private MvcResult finish(MvcResult first) throws Exception {
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(60_000);
            return mvc.perform(asyncDispatch(first)).andReturn();
        }
        return first;
    }

    public static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
