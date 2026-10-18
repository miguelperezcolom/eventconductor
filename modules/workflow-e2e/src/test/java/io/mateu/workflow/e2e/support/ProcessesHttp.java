package io.mateu.workflow.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.ProcessRestController;
import io.mateu.workflow.security.CallerResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


/**
 * The processes REST API driven through Spring MVC — dispatcher, access check, status mapping,
 * JSON — over the real engine the e2e context booted, without a servlet container. Every request
 * carries the API key the controller is configured with, as a real client would.
 */
public class ProcessesHttp {

    public static final String API_KEY = "e2e-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc;

    public ProcessesHttp(ProcessOperationsService service) {
        var apiProperties = new MessageApiProperties();
        apiProperties.setApiKey(API_KEY);
        var controller = new ProcessRestController(service, apiProperties,
                new StaticListableBeanFactory().getBeanProvider(CallerResolver.class));
        this.mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    public MockHttpServletResponse start(String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/workflow/api/processes").header("X-Api-Key", API_KEY)
                .contentType("application/json").content(body)).andReturn().getResponse();
    }

    public MockHttpServletResponse get(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                .get("/workflow/api/processes" + path).header("X-Api-Key", API_KEY)).andReturn().getResponse();
    }

    public MockHttpServletResponse post(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                .post("/workflow/api/processes" + path).header("X-Api-Key", API_KEY)).andReturn().getResponse();
    }

    /** The same GET without the key: what an anonymous caller is told. */
    public MockHttpServletResponse getWithoutKey(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/workflow/api/processes" + path)).andReturn().getResponse();
    }

    public static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    /** The process's status, read over HTTP. */
    public String status(String processId) throws Exception {
        return json(get("/" + processId)).path("status").asText();
    }
}
