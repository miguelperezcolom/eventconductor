package io.mateu.workflow.worker.llm;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Anthropic chat models. A class of its own so it is only loaded when spring-ai-anthropic is present.
 *
 * <p>{@code base-url} is the host (or proxy prefix) <em>without</em> the version segment: the Anthropic
 * SDK appends {@code /v1/messages} itself — the opposite of the {@code openai} provider. A {@code base-url}
 * written with {@code /v1} used to call {@code …/v1/v1/messages}; see {@link #apiRoot}.
 */
final class AnthropicModels {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnthropicModels.class);

    /**
     * The base URL the SDK is given for a configured {@code base-url}: trailing {@code /} dropped, and a
     * trailing {@code /v1} (or a pasted {@code /v1/messages} endpoint) cut off, since the SDK adds
     * {@code /v1/messages}. So {@code https://api.anthropic.com} and {@code https://api.anthropic.com/v1}
     * call the same {@code https://api.anthropic.com/v1/messages}.
     */
    static String apiRoot(String baseUrl) {
        var url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        for (var suffix : new String[]{"/v1/messages", "/v1"}) {
            if (url.endsWith(suffix)) {
                var root = url.substring(0, url.length() - suffix.length());
                log.info("Anthropic base-url {} ends in {}, which the client adds itself; using {}", baseUrl, suffix, root);
                return root;
            }
        }
        return url;
    }

    static ChatModel create(LlmWorkerProperties.Connection connection, ObservationRegistry observations) {
        var options = AnthropicChatOptions.builder()
                .apiKey(connection.getApiKey())
                .timeout(connection.getTimeout())
                .maxRetries(0);
        if (connection.getBaseUrl() != null && !connection.getBaseUrl().isBlank()) {
            options.baseUrl(apiRoot(connection.getBaseUrl()));
        }
        if (connection.getModel() != null && !connection.getModel().isBlank()) {
            options.model(connection.getModel());
        }
        if (connection.getMaxTokens() != null) {
            options.maxTokens(connection.getMaxTokens());
        }
        if (connection.getTemperature() != null) {
            options.temperature(connection.getTemperature());
        }
        return AnthropicChatModel.builder()
                .options(options.build())
                .observationRegistry(observations)
                .build();
    }

    private AnthropicModels() {
    }
}
