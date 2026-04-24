package io.mateu.workflow.controlplaneservice.infra.out.kv;

import io.mateu.workflow.controlplaneservice.infra.CloudflareProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CloudflareKvClient {

    private final RestClient restClient;
    private final CloudflareProperties props;

    public CloudflareKvClient(RestClient.Builder builder, CloudflareProperties props) {
        this.props = props;
        this.restClient = builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiToken())
                .build();
    }

    @Retryable(
            retryFor = CloudflareKvRetryableException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 300, multiplier = 2)
    )
    public void putValue(String key, String value) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);

        String uri = String.format(
                "/accounts/%s/storage/kv/namespaces/%s/values/%s",
                props.getAccountId(),
                props.getNamespaceId(),
                encodedKey
        );

        try {
            restClient.put()
                    .uri(uri)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(value)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CloudflareKvRetryableException(
                                "Cloudflare 5xx: " + res.getStatusCode()
                        );
                    })
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new CloudflareKvRetryableException(
                                "Cloudflare rate limit: " + res.getStatusCode()
                        );
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CloudflareKvException(
                                "Cloudflare 4xx no retryable: " + res.getStatusCode()
                        );
                    })
                    .toBodilessEntity();

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429) {
                throw new CloudflareKvRetryableException(
                        "Error retryable Cloudflare KV: " + ex.getStatusCode(),
                        ex
                );
            }

            throw new CloudflareKvException(
                    "Error no retryable Cloudflare KV: " + ex.getStatusCode()
                            + " - " + ex.getResponseBodyAsString(),
                    ex
            );
        }
    }

    @Retryable(
            retryFor = CloudflareKvRetryableException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 300, multiplier = 2)
    )
    public String getValue(String key) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);

        String uri = String.format(
                "/accounts/%s/storage/kv/namespaces/%s/values/%s",
                props.getAccountId(),
                props.getNamespaceId(),
                encodedKey
        );

        try {
            return restClient.get()
                    .uri(uri)
                    .accept(MediaType.TEXT_PLAIN)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new CloudflareKvNotFoundException("KV key not found: " + key);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CloudflareKvRetryableException(
                                "Cloudflare 5xx: " + res.getStatusCode()
                        );
                    })
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new CloudflareKvRetryableException(
                                "Cloudflare rate limit: " + res.getStatusCode()
                        );
                    })
                    .body(String.class);

        } catch (CloudflareKvNotFoundException ex) {
            return null;

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429) {
                throw new CloudflareKvRetryableException(
                        "Error retryable Cloudflare KV: " + ex.getStatusCode(),
                        ex
                );
            }

            throw new CloudflareKvException(
                    "Error no retryable Cloudflare KV: " + ex.getStatusCode(),
                    ex
            );
        }
    }

    @Retryable(
            retryFor = CloudflareKvRetryableException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 300, multiplier = 2)
    )
    public void putValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        List<KvBulkItem> items = values.entrySet()
                .stream()
                .map(e -> new KvBulkItem(e.getKey(), e.getValue()))
                .toList();

        for (List<KvBulkItem> batch : partition(items, 10_000)) {
            putValuesBatch(batch);
        }
    }

    private void putValuesBatch(List<KvBulkItem> batch) {
        String uri = String.format(
                "/accounts/%s/storage/kv/namespaces/%s/bulk",
                props.getAccountId(),
                props.getNamespaceId()
        );

        try {
            restClient.put()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batch)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CloudflareKvRetryableException(
                                "Cloudflare KV bulk 5xx: " + res.getStatusCode()
                        );
                    })
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new CloudflareKvRetryableException(
                                "Cloudflare KV bulk rate limit: " + res.getStatusCode()
                        );
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CloudflareKvException(
                                "Cloudflare KV bulk 4xx no retryable: " + res.getStatusCode()
                        );
                    })
                    .toBodilessEntity();

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429) {
                throw new CloudflareKvRetryableException(
                        "Error retryable Cloudflare KV bulk: " + ex.getStatusCode(),
                        ex
                );
            }

            throw new CloudflareKvException(
                    "Error no retryable Cloudflare KV bulk: "
                            + ex.getStatusCode()
                            + " - "
                            + ex.getResponseBodyAsString(),
                    ex
            );
        }
    }

    private static <T> List<List<T>> partition(List<T> source, int size) {
        List<List<T>> result = new ArrayList<>();

        for (int i = 0; i < source.size(); i += size) {
            result.add(source.subList(i, Math.min(i + size, source.size())));
        }

        return result;
    }

    public record KvBulkItem(String key, String value) {
    }
}