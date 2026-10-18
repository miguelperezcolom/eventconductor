package io.mateu.workflow.uie2e;

import io.mateu.workflow.uie2e.support.UiE2eApplication;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real application — the engine's consoles, an app UI at the root mounting WorkflowMenu, the
 * test worker's pages, Mateu's MCP — over a real socket, no browser: who gets in.
 *
 * <p>Raw HTTP on purpose, so that the request line reaches Tomcat exactly as written, encoded
 * characters and {@code ;parameters} included: no client gets to normalise the bypass attempts away.
 */
class ConsoleAccessTest {

    /** Paths that reach a console handler, in the spellings someone might try to sneak past a path rule with. */
    static final List<String> CONSOLE_GETS = List.of(
            "/_workflow", "/_workflow-admin", "/_workflow;jsessionid=x", "/%5Fworkflow", "/_workflow%3Bx=y",
            "/", "/;x=y", "/eventconductor/tasks/billing/module.zip",
            "/eventconductor/tasks/billing/module.zip;x=y", "/eventconductor/tasks/billing/module%2Ezip");

    static final List<String> CONSOLE_POSTS = List.of(
            "/_workflow/mateu/v3/workflow", "/_workflow/mateu/v3;x=y/workflow", "/%5Fworkflow/mateu/v3/workflow",
            "/_workflow-admin/mateu/v3/definitions", "/mateu/v3/workflow/processes",
            "/mateu/v3/sse/workflow/processes", "//mateu/v3/workflow/processes", "/mateu/mcp");

    record Response(int status, String body) { }

    static Response http(int port, String method, String path) throws IOException {
        try (var socket = new Socket("localhost", port)) {
            var body = "POST".equals(method) ? "{}" : "";
            var request = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/html\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            var out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            var text = out.toString(StandardCharsets.UTF_8);
            return new Response(Integer.parseInt(text.substring(9, 12)), text);
        }
    }

    @Nested
    @SpringBootTest(classes = UiE2eApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "workflow.message-api.allow-anonymous=false")
    class WithoutSpringSecurity {

        @LocalServerPort
        int port;

        @Test
        void everyConsoleIsRefusedWithAnExplanation() throws IOException {
            for (var path : CONSOLE_GETS) {
                var response = http(port, "GET", path);
                assertThat(response.status()).as("GET " + path).isIn(403, 400, 404);
                assertThat(response.body()).as("GET " + path).doesNotContain("<mateu-ui");
            }
            for (var path : CONSOLE_POSTS) {
                var response = http(port, "POST", path);
                assertThat(response.status()).as("POST " + path).isIn(403, 400, 404, 405);
                assertThat(response.body()).as("POST " + path).doesNotContain("\"uiIncrement").doesNotContain("\"result\"");
            }
            var page = http(port, "GET", "/_workflow");
            assertThat(page.status()).isEqualTo(403);
            assertThat(page.body()).contains("403 Forbidden", "Spring Security",
                    "workflow.message-api.allow-anonymous=true", "workflow.ui.enabled=false");
            assertThat(http(port, "GET", "/").status()).as("an app UI mounting WorkflowMenu").isEqualTo(403);
            assertThat(http(port, "GET", "/%5Fworkflow").status()).isEqualTo(403);
            assertThat(http(port, "POST", "/mateu/v3/workflow/processes").status()).isEqualTo(403);
            assertThat(http(port, "POST", "/_workflow/mateu/v3;x=y/workflow").status()).isIn(403, 400);
        }

        @Test
        void theEngineApiStillAnswersByItsOwnRule() throws IOException {
            assertThat(http(port, "POST", "/workflow/api/messages").status()).isEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(classes = UiE2eApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "workflow.message-api.allow-anonymous=true")
    class WithAllowAnonymous {

        @LocalServerPort
        int port;

        @Test
        void theConsolesAnswer() throws IOException {
            var page = http(port, "GET", "/_workflow");
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.body()).contains("<mateu-ui");
            assertThat(http(port, "GET", "/").status()).isEqualTo(200);
            assertThat(http(port, "GET", "/_workflow-admin").body()).contains("baseUrl=\"/_workflow-admin\"");
        }
    }

    @Nested
    @SpringBootTest(classes = UiE2eApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"workflow.ui.enabled=false", "workflow.message-api.allow-anonymous=true"})
    class WithTheUiDisabled {

        @LocalServerPort
        int port;

        @Test
        void theEnginesConsolesAreNotMounted() throws IOException {
            // Mateu's SPA filter hands an unknown GET to the application's own root UI, so a GET answers
            // that page: what matters is that it is not the engine's console.
            for (var path : List.of("/_workflow", "/_workflow-admin")) {
                var response = http(port, "GET", path);
                assertThat(response.body()).as(path).doesNotContain("baseUrl=\"" + path + "\"");
            }
            var zip = http(port, "GET", "/eventconductor/tasks/billing/module.zip");
            assertThat(zip.body()).as("no task-project download").doesNotContain("application/zip");
            for (var path : List.of("/_workflow/mateu/v3/workflow", "/_workflow-admin/mateu/v3/definitions")) {
                assertThat(http(port, "POST", path).status()).as(path + ": nothing handles it").isIn(404, 405);
            }
        }
    }
}
