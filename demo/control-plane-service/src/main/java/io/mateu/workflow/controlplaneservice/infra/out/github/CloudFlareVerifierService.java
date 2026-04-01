package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.usecases.ProgressReporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class CloudFlareVerifierService {

    private final RestClient restClient;

    public void verify(String deploymentId, ProgressReporter progressReporter) throws IOException {
        progressReporter.update(2, StatusType.WARNING);
        // 2. Verificación en Cloudflare (Polling)
        int maxAttempts = 30; // 2 minutos máximo (12 * 10s)
        String checkUrl = "https://riu-com-copy.miguelperezcolom.workers.dev";

        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(10000); // Esperar 10 segundos

                progressReporter.log("Checking version at " + checkUrl + " (try " + (i + 1) + ").");

                var response = restClient.get()
                        .uri(checkUrl)
                        .headers(headers -> headers.setBasicAuth("test", "test"))
                        .retrieve()
                        .toEntity(String.class);

                String resolvedVersion = response.getHeaders().getFirst("x-deployment-id");

                if (deploymentId.equals(resolvedVersion)) {
                    progressReporter.log("✅ Verified: Deployment " + deploymentId + " is live.");
                    progressReporter.update(2, StatusType.SUCCESS);
                    return;
                }
                progressReporter.log("⏳ Waiting deployment... (try " + (i + 1) + ")");

            } catch (Exception e) {
                // RestClient lanza excepciones específicas para 404, pero aquí las capturamos
                // para seguir reintentando mientras Cloudflare propaga el build.
                progressReporter.log("⏳ Cloudflare not responding (404) or it is propagating... " + e.getMessage());
            }
        }
        progressReporter.update(2, StatusType.DANGER);
        throw new RuntimeException("Timeout: GitHub aceptó el cambio, pero Cloudflare no desplegó a tiempo.");
    }
}