package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.workflow.controlplaneservice.infra.out.kv.CloudflareKvClient;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.*;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.fromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
@Primary
public class KVReleaseSettingPublisherService {

    final ReleaseEntityRepository releaseEntityRepository;
    final SiteEntityRepository siteEntityRepository;
    final CloudFlareVerifierService verifierService;
    final RouteEntityRepository routeEntityRepository;
    final StreamBridge streamBridge;
    private final PageEntityRepository pageEntityRepository;
    private final LanguageEntityRepository languageEntityRepository;
    final CloudflareKvClient cloudflareKvClient;

    public void setReleaseAndPublishToGitHub(
            String taskExecutionId,
            String deploymentId,
            String versionTag
    ) throws IOException {

        // 1. Construir mapa de rutas
        Map<String, String> releaseMap = new HashMap<>();

        routeEntityRepository.findAll()
                .stream()
                .filter(r -> r.getPlannedReleaseId() != null)
                .forEach(r -> releaseMap.put(
                        toKvRouteKey(r.getPath()),
                        "v" + r.getPlannedReleaseId()
                ));

        // 2. Publicar rutas en Cloudflare KV
        cloudflareKvClient.putValues(releaseMap);

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "Cloudflare KV updated with " + releaseMap.size() + " route mappings."
        ));

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "✅ Versión " + versionTag + " publicada en KV"
        ));
    }

    private String toKvRouteKey(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "route:/";
        }

        String normalized = path.trim();

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return "route:" + normalized;
    }
}