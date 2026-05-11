package io.mateu.workflow.iaagentservice;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;

/**
 * Intercepts Anthropic /messages requests to inject cache_control on the system prompt,
 * and logs cache token stats from the response.
 *
 * Spring AI 1.0.0 sends `system` as a plain string. Anthropic's API also accepts it as an
 * array of content blocks, which is the format required for server-side caching.
 * Cache reads cost ~10% of normal input token price.
 *
 * Requires the RestClient to use a BufferingClientHttpRequestFactory so that the
 * response body can be read more than once (configured in AnthropicCacheConfig).
 */
@Component
public class AnthropicCacheInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCacheInterceptor.class);

    private final ObjectMapper mapper;

    public AnthropicCacheInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (body.length > 0 && request.getURI().getPath().endsWith("/messages")) {
            body = injectSystemCacheControl(body);
        }
        ClientHttpResponse response = execution.execute(request, body);
        if (request.getURI().getPath().endsWith("/messages")) {
            logCacheUsage(response);
        }
        return response;
    }

    private byte[] injectSystemCacheControl(byte[] body) {
        try {
            var root = (ObjectNode) mapper.readTree(body);
            var systemNode = root.get("system");
            if (systemNode != null && systemNode.isTextual()) {
                var block = mapper.createObjectNode();
                block.put("type", "text");
                block.put("text", systemNode.asText());
                block.set("cache_control", mapper.createObjectNode().put("type", "ephemeral"));
                root.set("system", mapper.createArrayNode().add(block));
                return mapper.writeValueAsBytes(root);
            }
        } catch (Exception e) {
            log.warn("Could not inject cache_control into Anthropic request: {}", e.getMessage());
        }
        return body;
    }

    private void logCacheUsage(ClientHttpResponse response) {
        // Response body can only be read once; BufferingClientHttpRequestFactory in
        // AnthropicCacheConfig ensures the body is buffered so Spring AI can still read it.
        try {
            byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
            var root = mapper.readTree(responseBody);
            var usage = root.get("usage");
            if (usage != null) {
                int cacheRead   = usage.path("cache_read_input_tokens").asInt(0);
                int cacheCreate = usage.path("cache_creation_input_tokens").asInt(0);
                int inputTokens = usage.path("input_tokens").asInt(0);
                if (cacheRead > 0 || cacheCreate > 0) {
                    log.info("Anthropic cache — read={} tokens, write={} tokens, uncached={} tokens",
                            cacheRead, cacheCreate, inputTokens);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read cache usage from response: {}", e.getMessage());
        }
    }

}
