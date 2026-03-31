package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.usecases.ProgressReporter;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.ResourceEntityRepository;
import lombok.RequiredArgsConstructor;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class GitHubReleaseFolderPublisherService {

    final ResourceEntityRepository resourceEntityRepository;
    final CloudFlareVerifierService publisher;

    @Value("${github.token}")
    private String githubToken;

    @Value("${github.repo}")
    private String repoName;

    public void publishReleaseFolderToGitHub(String versionTag, ProgressReporter progressReporter) throws IOException {
        // 1. Conectar con GitHub
        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();
        GHRepository repository = github.getRepository(repoName);

        // 2. Obtener el SHA del último commit de la rama principal (master/main)
        String baseTreeSha = repository.getRef("heads/main").getObject().getSha();

        // 3. Crear el "Tree" con los archivos locales
        GHTreeBuilder treeBuilder = repository.createTree().baseTree(baseTreeSha);

            resourceEntityRepository.findAll().forEach(resource -> {
                try {
                    String relativePath = "public/v" + versionTag;
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
                    treeBuilder.add(relativePath, html, false);

                    progressReporter.log("" + relativePath + " added.");
                } catch (IOException e) {
                    throw new RuntimeException("Error leyendo archivo para GitHub", e);
                }
            });

        GHTree newTree = treeBuilder.create();

        progressReporter.update(0, StatusType.SUCCESS);
        progressReporter.update(1, StatusType.WARNING);

        // 4. Crear el Commit
        GHCommit commit = repository.createCommit()
                .message("Release " + versionTag + " auto-generated")
                .tree(newTree.getSha())
                .parent(baseTreeSha)
                .create();

        // 5. Actualizar la rama para que apunte al nuevo commit
        repository.getRef("heads/main").updateTo(commit.getSHA1());

        progressReporter.log("✅ Versión " + versionTag + " publicada en GitHub: " + commit.getSHA1());
    }


    public void publishReleaseFolderAndVerify(String versionTag, ProgressReporter progressReporter) throws IOException {
        progressReporter.update(0, StatusType.WARNING);
        publishReleaseFolderToGitHub(versionTag, progressReporter);
        progressReporter.update(1, StatusType.SUCCESS);
    }

}