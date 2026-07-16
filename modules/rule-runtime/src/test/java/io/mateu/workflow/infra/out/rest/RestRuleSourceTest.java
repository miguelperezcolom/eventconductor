package io.mateu.workflow.infra.out.rest;

import com.sun.net.httpserver.HttpServer;
import io.mateu.workflow.application.services.RuleJsonMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestRuleSourceTest {

    static final String RULE_JSON = """
            {"id": "r1", "name": "Rule one", "type": "expression", "when": "true",
             "then": [{"name": "out", "expression": "1"}]}
            """;

    static HttpServer server;
    static RestRuleSource source;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rules", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var body = switch (path) {
                case "/rules" -> "[" + RULE_JSON + "]";
                case "/rules/r1" -> RULE_JSON;
                case "/rules/boom" -> "oops";
                default -> null;
            };
            var status = body == null ? 404 : "oops".equals(body) ? 500 : 200;
            var bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        source = new RestRuleSource("http://localhost:" + server.getAddress().getPort() + "/",
                new RuleJsonMapper());
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesRuleById() {
        assertThat(source.findById("r1")).hasValueSatisfying(rule ->
                assertThat(rule.name()).isEqualTo("Rule one"));
    }

    @Test
    void notFoundIsEmpty() {
        assertThat(source.findById("missing")).isEmpty();
    }

    @Test
    void fetchesAllRules() {
        assertThat(source.findAll()).hasSize(1);
    }

    @Test
    void serverErrorsAreReported() {
        assertThatThrownBy(() -> source.findById("boom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void unreachableCatalogIsReported() {
        var unreachable = new RestRuleSource("http://localhost:1", new RuleJsonMapper());
        assertThatThrownBy(unreachable::findAll).isInstanceOf(IllegalStateException.class);
    }
}
