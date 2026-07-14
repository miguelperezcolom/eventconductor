package io.mateu.workflow.infra.out.rest;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Rule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Fetches rules from the catalog's REST read API ({@code GET /rules},
 * {@code GET /rules/&#123;id&#125;}). Plain JDK HttpClient — no framework needed.
 * Usually wrapped in a CachingRuleSource.
 */
public class RestRuleSource implements RuleSource {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;
    private final RuleJsonMapper ruleJsonMapper;

    public RestRuleSource(String baseUrl, RuleJsonMapper ruleJsonMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.ruleJsonMapper = ruleJsonMapper;
    }

    @Override
    public Optional<Rule> findById(String id) {
        var response = get("/rules/" + id);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        checkOk(response);
        return Optional.of(ruleJsonMapper.toRule(response.body()));
    }

    @Override
    public List<Rule> findAll() {
        var response = get("/rules");
        checkOk(response);
        return ruleJsonMapper.toRuleList(response.body());
    }

    private HttpResponse<String> get(String path) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the rule catalog", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reach the rule catalog at " + baseUrl, e);
        }
    }

    private void checkOk(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Rule catalog returned " + response.statusCode() + " for " + response.uri());
        }
    }
}
