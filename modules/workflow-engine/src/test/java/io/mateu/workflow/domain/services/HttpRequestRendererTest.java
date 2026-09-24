package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.HttpCall;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRequestRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Step STEP = new Step("call", "wd", StepType.HTTP_CALL, "Call", null, "start", null, null,
            false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    private static final Process PROCESS = Process.builder().id("p").businessKey("BK")
            .variables(List.of(new Variable("id", "7"), new Variable("xml", "<a/>"))).build();

    private static HttpCall http(String connection, String url, Object body, String bodyTemplate, Object auth) {
        return new HttpCall(connection, null, url, null, null, null, body, bodyTemplate, List.of(200), Map.of("v", "body.x"), auth, null);
    }

    @Test
    void anAbsoluteUrlWithATextBodyAndInlineAuthRendersWithoutResolvingTheSecret() throws Exception {
        var rendered = JSON.readTree(HttpRequestRenderer.render(STEP.withHttp(http(null, "https://api.x.com/o/${id}",
                null, "<order id=\"${id}\"/>", Map.of("type", "bearer", "token", "${secret:T}"))), PROCESS, "se-1"));
        assertThat(rendered.path("method").asText()).isEqualTo("GET");
        assertThat(rendered.path("url").asText()).isEqualTo("https://api.x.com/o/7");
        assertThat(rendered.path("body").asText()).isEqualTo("<order id=\"7\"/>");
        assertThat(rendered.path("bodyKind").asText()).isEqualTo("text");
        assertThat(rendered.path("auth").path("token").asText()).isEqualTo("${secret:T}");
        assertThat(rendered.path("successStatus").toString()).isEqualTo("[200]");
    }

    @Test
    void problemsCoverStructureTemplatesAndOutputExpressions() {
        assertThat(HttpRequestRenderer.problems(STEP.withHttp(http("c", null, Map.of("a", "${id +}"), null, null))))
                .anyMatch(p -> p.contains("http.body"));
        assertThat(HttpRequestRenderer.problems(STEP.withHttp(new HttpCall("c", null, null, null, null, null, null, null,
                null, Map.of("v", "body.("), null, null)))).anyMatch(p -> p.contains("http.output.v"));
        assertThat(HttpRequestRenderer.problems(STEP)).anyMatch(p -> p.contains("http block"));
        assertThat(HttpRequestRenderer.problems(STEP.withHttp(http(null, "https://x", null, null,
                Map.of("type", "bearer", "token", "${secret:T}"))))).isEmpty();
        assertThatThrownBy(() -> HttpRequestRenderer.render(STEP, PROCESS, "se")).hasMessageContaining("no http block");
    }
}
