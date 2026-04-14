package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class CloudFlareVerifierService {

    private final RestClient restClient;
    final StreamBridge streamBridge;

    public void verify(String taskExecutionId, String deploymentId) throws IOException {
        // 2. Verificación en Cloudflare (Polling)
        int maxAttempts = 30; // 2 minutos máximo (12 * 10s)
        String checkUrl = "https://riu-com-copy.miguelperezcolom.workers.dev";

        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(10000); // Esperar 10 segundos

                streamBridge.send("upstream", new TaskLogEmitted(
                        taskExecutionId,
                        MessageType.Info,
                        "Checking version at " + checkUrl + " (try " + (i + 1) + ")."));

                var response = restClient.get()
                        .uri(checkUrl)
                        .headers(headers -> headers.setBasicAuth("test", "test"))
                        .retrieve()
                        .toEntity(String.class);

                String resolvedVersion = response.getHeaders().getFirst("x-deployment-id");

                if (deploymentId.equals(resolvedVersion)) {
                    streamBridge.send("upstream", new TaskLogEmitted(
                            taskExecutionId,
                            MessageType.Info,
                            "✅ Verified: Deployment " + deploymentId + " is live."));
                    return;
                }
                streamBridge.send("upstream", new TaskLogEmitted(
                        taskExecutionId,
                        MessageType.Info,
                        "⏳ Waiting deployment... (try " + (i + 1) + ")"));

            } catch (Exception e) {
                // RestClient lanza excepciones específicas para 404, pero aquí las capturamos
                // para seguir reintentando mientras Cloudflare propaga el build.
                streamBridge.send("upstream", new TaskLogEmitted(
                        taskExecutionId,
                        MessageType.Info,
                        "⏳ Cloudflare not responding (404) or it is propagating... " + e.getMessage()));
            }
        }
        throw new RuntimeException("Timeout: GitHub aceptó el cambio, pero Cloudflare no desplegó a tiempo.");
    }
}