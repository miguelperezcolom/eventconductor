package io.mateu.workflow.autoconfigure;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * The OpenAPI description of the engine's REST API ({@code /workflow/api/**}), served by springdoc
 * as the {@value #GROUP} group: {@code /v3/api-docs/eventconductor} (JSON) and
 * {@code /v3/api-docs.yaml/eventconductor}.
 *
 * <p>Off unless both are true: the application brings springdoc
 * ({@code org.springdoc:springdoc-openapi-starter-webmvc-api}, an optional dependency of the engine)
 * and sets {@code workflow.api.openapi.enabled=true}. The engine never switches springdoc on or off
 * for the host application's own endpoints — it only contributes its group.
 *
 * <p>The same description is generated at build time into the documentation site
 * ({@code doc/public/openapi/eventconductor-api.yaml}) by {@code EngineOpenApiSpecTest}, which also
 * fails the build when the committed file drifts from the controllers.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.models.GroupedOpenApi")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.api.openapi.enabled", havingValue = "true")
public class WorkflowOpenApiAutoConfiguration {

    /** The springdoc group the engine's API is published under. */
    public static final String GROUP = "eventconductor";

    /** The security scheme every operation names: the {@code X-Api-Key} header. */
    public static final String API_KEY_SCHEME = "apiKey";

    @Bean
    GroupedOpenApi eventConductorApi() {
        return engineApi();
    }

    /** The group, also built by the spec test so the committed file and the served one are the same. */
    public static GroupedOpenApi engineApi() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .displayName("EventConductor engine API")
                .pathsToMatch("/workflow/api/**")
                .addOpenApiCustomizer(openApi -> {
                    openApi.info(new Info()
                            .title("EventConductor engine API")
                            .version("v1")
                            .description("The engine's REST API under /workflow/api: processes, messages,"
                                    + " events and triggers, schedules and synchronous invocations."
                                    + " Every operation takes the X-Api-Key header when"
                                    + " workflow.message-api.api-key is set; with Spring Security the"
                                    + " application's own authentication applies instead.")
                            .license(new License().name("Apache-2.0")
                                    .url("https://www.apache.org/licenses/LICENSE-2.0")));
                    // Relative: the description is the same whichever host serves it.
                    openApi.servers(List.of(new Server().url("/")));
                    var components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
                    components.addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.HEADER)
                            .name("X-Api-Key"));
                    openApi.components(components);
                    openApi.security(List.of(new SecurityRequirement().addList(API_KEY_SCHEME)));
                    if (openApi.getPaths() != null) {
                        openApi.getPaths().values().forEach(path -> path.readOperations()
                                .forEach(WorkflowOpenApiAutoConfiguration::tidy));
                    }
                })
                .build();
    }

    /**
     * Two corrections to what springdoc infers. The {@code X-Api-Key} header every controller reads
     * is the {@value #API_KEY_SCHEME} security scheme, not a parameter of each operation; and a JSON
     * body is {@code application/json}, which springdoc writes as {@code *}{@code /*} when a mapping
     * does not narrow what it produces.
     */
    static void tidy(Operation operation) {
        if (operation.getParameters() != null) {
            operation.getParameters().removeIf(parameter ->
                    "header".equals(parameter.getIn()) && "X-Api-Key".equalsIgnoreCase(parameter.getName()));
            if (operation.getParameters().isEmpty()) {
                operation.setParameters(null);
            }
        }
        if (operation.getResponses() != null) {
            operation.getResponses().values().forEach(response -> {
                var content = response.getContent();
                if (content != null && content.containsKey("*/*")) {
                    var media = content.remove("*/*");
                    content.addMediaType("application/json", media);
                }
            });
        }
    }
}
