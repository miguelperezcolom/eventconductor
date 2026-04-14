package io.mateu.workflow.controlplaneservice.infra.out.r2;

import io.mateu.workflow.controlplaneservice.infra.out.github.CloudFlareVerifierService;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.ResourceEntityRepository;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Primary
public class R2ReleaseFolderPublisherService {

    final ResourceEntityRepository resourceEntityRepository;
    final CloudFlareVerifierService publisher;
    final StreamBridge streamBridge;

    public void publishReleaseFolderToGitHub(String versionTag, String stepExecutionId) throws IOException {
        // 1. Conectar con GitHub
        var client = R2Client.getClient();


            resourceEntityRepository.findAll().forEach(resource -> {
                try {
                    String relativePath = "v" + versionTag;
                    if (!resource.getPath().startsWith("/")) {
                        relativePath += "/";
                    }
                    relativePath += resource.getPath();
                    // Subimos el contenido del archivo
                    byte[] content = resource.getContent();
                    String html = new String(content, "UTF-8");
                    html = html.replaceAll("Home x v2", "Home " + versionTag);
                    html = html.replaceAll("</body", """
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "Hotel",
  "name": "Hotel Paradise Beach Resort",
  "description": "Resort de lujo frente al mar con todo incluido, ideal para vacaciones familiares y escapadas románticas.",
  "url": "https://www.paradisebeachresort.com",
  "telephone": "+34 971 123 456",
  "address": {
    "@type": "PostalAddress",
    "streetAddress": "Avenida del Mar 25",
    "addressLocality": "Palma",
    "addressRegion": "Islas Baleares",
    "postalCode": "07001",
    "addressCountry": "ES"
  },
  "geo": {
    "@type": "GeoCoordinates",
    "latitude": 39.5696,
    "longitude": 2.6502
  },
  "priceRange": "€€€",
  "starRating": {
    "@type": "Rating",
    "ratingValue": "5"
  },
  "amenityFeature": [
    {
      "@type": "LocationFeatureSpecification",
      "name": "Piscina",
      "value": true
    },
    {
      "@type": "LocationFeatureSpecification",
      "name": "WiFi gratuito",
      "value": true
    },
    {
      "@type": "LocationFeatureSpecification",
      "name": "Spa",
      "value": true
    },
    {
      "@type": "LocationFeatureSpecification",
      "name": "Gimnasio",
      "value": true
    }
  ],
  "checkinTime": "15:00",
  "checkoutTime": "12:00",
  "petsAllowed": false,
  "image": [
    "https://www.paradisebeachresort.com/images/hotel1.jpg",
    "https://www.paradisebeachresort.com/images/pool.jpg"
  ],
  "aggregateRating": {
    "@type": "AggregateRating",
    "ratingValue": "4.5",
    "reviewCount": "1248"
  }
}
</script>                                         
                                            """ +
                            "</body");
                    html = html.replaceAll("</body",
                            "<!-- RIU VERSION " + versionTag + " -->\n" +
                                    "<!-- RIU RESOURCE " + resource.getName() + " -->\n" +
                                    "</body");

                    String bucketName = "riu-assets";
                    String key = "v21/es/index_ES.html"; // La ruta/nombre dentro del bucket
                    key = relativePath;

                    PutObjectRequest putOb = PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .contentType("text/html") // Importante para que el navegador lo renderice bien
                            .build();

                    client.putObject(putOb, RequestBody.fromString(html));

                    streamBridge.send("upstream", new TaskLogEmitted(
                            stepExecutionId,
                            MessageType.Info,
                            "" + relativePath + " added."));
                } catch (IOException e) {
                    throw new RuntimeException("Error leyendo archivo para R2", e);
                }
            });

        streamBridge.send("upstream", new TaskLogEmitted(
                stepExecutionId,
                MessageType.Info,
                "✅ Versión " + versionTag + " publicada en R2"));
    }


    public void publishReleaseFolderAndVerify(String versionTag, String stepExecutionId) throws IOException {
        publishReleaseFolderToGitHub(versionTag, stepExecutionId);
    }

}