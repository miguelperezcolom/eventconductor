package io.mateu.workflow.worker.llm;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * OpenAI and OpenAI-compatible chat models (vLLM, Ollama, LiteLLM, Azure OpenAI's compatible
 * endpoint… through {@code base-url}). A class of its own so it is only loaded when spring-ai-openai is
 * present.
 *
 * <p>{@code base-url} is the API root <em>including the version segment</em> — what comes before
 * {@code /chat/completions}: {@code https://api.openai.com/v1}, {@code http://vllm.internal:8000/v1}.
 * The client (Spring AI 2 over the OpenAI Java SDK) appends {@code chat/completions} to it as given, so
 * a bare host used to call {@code http://host:8000/chat/completions}, which vLLM, Ollama and most
 * compatible servers answer with 404. Two forms are therefore tolerated (see {@link #apiRoot}): a URL
 * with no path gets {@code /v1}, and a full {@code …/chat/completions} endpoint loses that suffix.
 */
final class OpenAiModels {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiModels.class);

    private static final String COMPLETIONS = "/chat/completions";

    static ChatModel create(LlmWorkerProperties.Connection connection, ObservationRegistry observations) {
        var options = OpenAiChatOptions.builder()
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
        return OpenAiChatModel.builder()
                .options(options.build())
                .observationRegistry(observations)
                .build();
    }

    /**
     * The API root the client is given for a configured {@code base-url}:
     * <ul>
     *   <li>a URL with a path is used as written, trailing {@code /} dropped —
     *       {@code http://host:8000/v1}, {@code https://gateway/openai/v1}, {@code http://app/fake/llm};</li>
     *   <li>a URL with no path (just scheme, host and port) gets {@code /v1} —
     *       {@code http://vllm.internal:8000} becomes {@code http://vllm.internal:8000/v1};</li>
     *   <li>a URL ending in {@code /chat/completions} (the endpoint pasted whole) loses that suffix.</li>
     * </ul>
     * So {@code http://host:8000} and {@code http://host:8000/v1} call the same
     * {@code http://host:8000/v1/chat/completions}, and {@code /v1} is never doubled.
     */
    static String apiRoot(String baseUrl) {
        var url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith(COMPLETIONS)) {
            var root = url.substring(0, url.length() - COMPLETIONS.length());
            log.info("LLM base-url {} is a full endpoint; using {} as the API root", baseUrl, root);
            return root;
        }
        int scheme = url.indexOf("://");
        boolean hasPath = url.indexOf('/', scheme < 0 ? 0 : scheme + 3) >= 0;
        if (!hasPath) {
            log.info("LLM base-url {} has no path; using {}/v1 as the API root", baseUrl, url);
            return url + "/v1";
        }
        return url;
    }

    private OpenAiModels() {
    }
}
