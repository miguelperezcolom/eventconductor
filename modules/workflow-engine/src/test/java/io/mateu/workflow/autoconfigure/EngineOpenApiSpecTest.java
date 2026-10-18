package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.EventRestController;
import io.mateu.workflow.infra.in.rest.MessageRestController;
import io.mateu.workflow.infra.in.rest.ProcessRestController;
import io.mateu.workflow.infra.in.rest.ScheduleRestController;
import io.mateu.workflow.infra.in.rest.SyncInvocationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The OpenAPI description of {@code /workflow/api/**}, generated from the controllers by springdoc
 * exactly as an application serves it ({@code workflow.api.openapi.enabled=true}), and compared with
 * the copy committed to the documentation site, {@value #SPEC}.
 *
 * <p>A controller change that alters the API fails this test until the committed file is regenerated:
 * <pre>
 *   EC_UPDATE_OPENAPI=true mvn -pl modules/workflow-engine -am verify -Dtest=EngineOpenApiSpecTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * and the diff of the YAML is the review of the API change.
 */
@SpringBootTest(classes = EngineOpenApiSpecTest.SpecApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "workflow.api.openapi.enabled=true",
                "springdoc.writer-with-order-by-keys=true",
                "springdoc.writer-with-default-pretty-printer=true",
        })
class EngineOpenApiSpecTest {

    /** Relative to the module, which is the working directory surefire runs in. */
    static final String SPEC = "../../doc/public/openapi/eventconductor-api.yaml";

    @Configuration
    @ImportAutoConfiguration({
            org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
            org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
            org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
            org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
            org.springdoc.core.configuration.SpringDocConfiguration.class,
            org.springdoc.core.properties.SpringDocConfigProperties.class,
            org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration.class,
            org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
            org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class,
            WorkflowOpenApiAutoConfiguration.class,
    })
    @EnableConfigurationProperties(MessageApiProperties.class)
    @Import({ProcessRestController.class, MessageRestController.class, EventRestController.class,
            ScheduleRestController.class, SyncInvocationController.class})
    static class SpecApplication {
    }

    @MockitoBean ProcessOperationsService processOperationsService;
    @MockitoBean MessageDispatcher messageDispatcher;
    @MockitoBean InboundEventService inboundEventService;
    @MockitoBean EventTriggerService eventTriggerService;
    @MockitoBean ScheduleService scheduleService;
    @MockitoBean SyncInvocationService syncInvocationService;
    @MockitoBean SyncReplyWaiters syncReplyWaiters;
    @MockitoBean WorkflowMetrics workflowMetrics;

    @Autowired WebApplicationContext context;

    @Test
    void theCommittedSpecIsTheOneTheControllersDescribe() throws Exception {
        var generated = MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/v3/api-docs.yaml/" + WorkflowOpenApiAutoConfiguration.GROUP))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(generated).contains("/workflow/api/processes").contains("X-Api-Key");

        var spec = Path.of(SPEC);
        var normalised = normalise(generated);
        if ("true".equalsIgnoreCase(System.getenv("EC_UPDATE_OPENAPI"))
                || Boolean.getBoolean("ec.updateOpenapi") || !Files.exists(spec)) {
            Files.createDirectories(spec.getParent());
            Files.writeString(spec, normalised, StandardCharsets.UTF_8);
            if (!"true".equalsIgnoreCase(System.getenv("EC_UPDATE_OPENAPI")) && !Boolean.getBoolean("ec.updateOpenapi")) {
                fail(SPEC + " did not exist; it has been generated — commit it.");
            }
            return;
        }
        var committed = normalise(Files.readString(spec, StandardCharsets.UTF_8));
        if (!committed.equals(normalised)) {
            fail(SPEC + " is out of date with the engine's REST controllers. Regenerate it with"
                    + " EC_UPDATE_OPENAPI=true and commit the result (see this test's javadoc).");
        }
    }

    private static String normalise(String yaml) {
        return yaml.replace("\r\n", "\n").stripTrailing() + "\n";
    }
}
