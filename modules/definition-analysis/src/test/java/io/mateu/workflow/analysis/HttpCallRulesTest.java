package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCallRulesTest {

    private static Map<String, Object> http(Object... pairs) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void aConnectionOrAnAbsoluteUrlWithReferencedSecretsIsValid() {
        assertThat(HttpCallRules.problems(http("connection", "payments", "method", "post", "path", "/x"), "s")).isEmpty();
        assertThat(HttpCallRules.problems(http("url", "https://a.b/c", "auth",
                Map.of("type", "bearer", "token", "${secret:PARTNER_TOKEN}"), "retryOn", List.of("5xx", "429")), "s")).isEmpty();
        assertThat(HttpCallRules.problems(http("url", "https://a.b/c", "auth", "partner-key"), "s")).isEmpty();
    }

    @Test
    void theRulesRefuseWhatCouldNotWorkOrWouldLeakACredential() {
        assertThat(HttpCallRules.problems(null, "s")).singleElement().asString().contains("http block");
        assertThat(HttpCallRules.problems(http(), "s")).anyMatch(p -> p.contains("exactly one of connection and url"));
        assertThat(HttpCallRules.problems(http("connection", "c", "url", "https://x"), "s")).anyMatch(p -> p.contains("exactly one"));
        assertThat(HttpCallRules.problems(http("url", "https://x", "path", "/p"), "s")).anyMatch(p -> p.contains("path is only"));
        assertThat(HttpCallRules.problems(http("connection", "c", "method", "BREW"), "s")).anyMatch(p -> p.contains("method"));
        assertThat(HttpCallRules.problems(http("connection", "c", "body", Map.of(), "bodyTemplate", "x"), "s")).anyMatch(p -> p.contains("both body"));
        assertThat(HttpCallRules.problems(http("connection", "c", "retryOn", List.of("often")), "s")).anyMatch(p -> p.contains("retryOn"));
        assertThat(HttpCallRules.problems(http("url", "https://x", "auth", Map.of("type", "bearer", "token", "abc123")), "s"))
                .anyMatch(p -> p.contains("auth.token must be a ${secret:NAME}"));
        assertThat(HttpCallRules.problems(http("connection", "c", "headers", Map.of("Authorization", "Bearer ${secret:T}")), "s"))
                .anyMatch(p -> p.contains("only allowed in an inline auth"));
        assertThat(HttpCallRules.problems(http("connection", "c", "auth", 42), "s")).anyMatch(p -> p.contains("profile name"));
    }
}
