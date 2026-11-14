package io.mateu.workflow.worker.llm;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an {@code openai} connection's {@code base-url} means: the API root including {@code /v1}. The
 * client appends {@code chat/completions} to it, so a bare host would call {@code /chat/completions};
 * both the bare host and the {@code /v1} form call {@code /v1/chat/completions}, and a pasted full
 * endpoint is not doubled.
 */
class OpenAiBaseUrlTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            var body = ("{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @ParameterizedTest(name = "base-url {0} calls {1}")
    @CsvSource({
            "'',                        /v1/chat/completions",
            "/,                         /v1/chat/completions",
            "/v1,                       /v1/chat/completions",
            "/v1/,                      /v1/chat/completions",
            "/v1/chat/completions,      /v1/chat/completions",
            "/openai/v1,                /openai/v1/chat/completions",
            "/fake/llm,                 /fake/llm/chat/completions",
    })
    void callsTheChatCompletionsEndpointUnderTheApiRoot(String suffix, String expectedPath) {
        var connection = new LlmWorkerProperties.Connection();
        connection.setProvider("openai");
        connection.setBaseUrl(base() + suffix);
        connection.setApiKey("k");
        connection.setModel("m");

        var response = OpenAiModels.create(connection, ObservationRegistry.NOOP).call(new Prompt("hello"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("hi");
        assertThat(paths).containsExactly(expectedPath);
    }

    @Test
    void apiRootKeepsAPathAndAddsV1OnlyToABareHost() {
        assertThat(OpenAiModels.apiRoot("https://api.openai.com")).isEqualTo("https://api.openai.com/v1");
        assertThat(OpenAiModels.apiRoot("https://api.openai.com/v1")).isEqualTo("https://api.openai.com/v1");
        assertThat(OpenAiModels.apiRoot(" http://vllm.internal:8000/ ")).isEqualTo("http://vllm.internal:8000/v1");
        assertThat(OpenAiModels.apiRoot("https://gw.example.com/openai/v1/chat/completions"))
                .isEqualTo("https://gw.example.com/openai/v1");
        assertThat(OpenAiModels.apiRoot("http://localhost:8095/fake/llm")).isEqualTo("http://localhost:8095/fake/llm");
    }
}
