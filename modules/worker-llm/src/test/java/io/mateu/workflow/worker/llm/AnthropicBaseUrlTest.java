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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an {@code anthropic} connection's {@code base-url} means: the host without {@code /v1}, which the
 * SDK adds with {@code /v1/messages}; a {@code base-url} written with {@code /v1} is not doubled.
 */
class AnthropicBaseUrlTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(400, -1);   // the path is all this test is about
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @ParameterizedTest(name = "base-url {0} calls {1}")
    @CsvSource({
            "'',              /v1/messages",
            "/,               /v1/messages",
            "/v1,             /v1/messages",
            "/v1/messages,    /v1/messages",
            "/proxy,          /proxy/v1/messages",
            "/proxy/v1/,      /proxy/v1/messages",
    })
    void callsV1MessagesUnderTheHost(String suffix, String expectedPath) {
        var connection = new LlmWorkerProperties.Connection();
        connection.setProvider("anthropic");
        connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + suffix);
        connection.setApiKey("k");
        connection.setModel("m");

        try {
            AnthropicModels.create(connection, ObservationRegistry.NOOP).call(new Prompt("hello"));
        } catch (RuntimeException expected) {
            // the stub answers 400
        }

        assertThat(paths).isNotEmpty().allMatch(expectedPath::equals);
    }

    @Test
    void apiRootDropsTheVersionTheSdkAdds() {
        assertThat(AnthropicModels.apiRoot("https://api.anthropic.com/v1")).isEqualTo("https://api.anthropic.com");
        assertThat(AnthropicModels.apiRoot("https://api.anthropic.com/")).isEqualTo("https://api.anthropic.com");
        assertThat(AnthropicModels.apiRoot("https://gw.example.com/anthropic")).isEqualTo("https://gw.example.com/anthropic");
    }
}
