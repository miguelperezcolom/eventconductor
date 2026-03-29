package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.DeployUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.ReleaseEntityRepository;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.ResourceEntityRepository;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.SiteEntityRepository;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
public class GitHubPublisherService {

    @Autowired
    private ReleaseEntityRepository releaseEntityRepository;

    @Autowired
    private SiteEntityRepository siteEntityRepository;

    @Autowired
    private ResourceEntityRepository resourceEntityRepository;

    @Autowired
    private RestClient restClient; // Necesitarás definir este Bean en tu configuración

    @Value("${github.token}")
    private String githubToken;

    @Value("${github.repo}")
    private String repoName; // Ej: "usuario/eventconductor"

    @Value("classpath:github/wrangler.jsonc")
    private Resource wranglerResource;
    @Autowired
    private RouteRepository routeRepository;

    public void publishToGitHub(String versionTag, Path localPath, DeployUseCase deployUseCase) throws IOException {
        // 1. Conectar con GitHub
        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();
        GHRepository repository = github.getRepository(repoName);

        // 2. Obtener el SHA del último commit de la rama principal (master/main)
        String baseTreeSha = repository.getRef("heads/main").getObject().getSha();

        // 3. Crear el "Tree" con los archivos locales
        GHTreeBuilder treeBuilder = repository.createTree().baseTree(baseTreeSha);

        if (localPath != null) {
            // Recorremos los archivos generados por el Release Creator
            Files.walk(localPath).forEach(file -> {
                if (Files.isRegularFile(file)) {
                    try {
                        String relativePath = localPath.relativize(file).toString().replace("\\", "/");
                        relativePath = relativePath.replaceAll("vxxxxxx", versionTag);
                        // Subimos el contenido del archivo
                        byte[] content = Files.readAllBytes(file);
                        String html = new String(content, "UTF-8");
                        html = html.replaceAll("Home x v2", "Home " + versionTag);
                        treeBuilder.add(relativePath, html, false);
                    } catch (IOException e) {
                        throw new RuntimeException("Error leyendo archivo para GitHub", e);
                    }
                }
            });
        } else {
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
                            deployUseCase.getMessages().add(0, new Message("x", "" + deployUseCase.getMessages().size(), LocalDateTime.now(),
                                    "" + relativePath + " added."));
                        } catch (IOException e) {
                            throw new RuntimeException("Error leyendo archivo para GitHub", e);
                        }
            });
        }

        // Leer contenido directamente
        var siteId = releaseEntityRepository.findById(Long.valueOf(versionTag)).orElseThrow().getSiteId();
        String llmsTxt = siteEntityRepository.findById(siteId).orElseThrow().getLlmsTxt();
        // Publicar
        treeBuilder.add("public/llms.txt", llmsTxt.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);

        deployUseCase.getMessages().add(0, new Message("x", "" + deployUseCase.getMessages().size(), LocalDateTime.now(),
                "llms.txt added."));


        // Leer contenido directamente
        String wranglerJson = wranglerResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        wranglerJson = wranglerJson.replace("v0000000002", "v" + versionTag);
        // Publicar
        treeBuilder.add("wrangler.jsonc", wranglerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        deployUseCase.getMessages().add(0, new Message("x", "" + deployUseCase.getMessages().size(), LocalDateTime.now(),
                "wrangler.jsonc updated."));


        GHTree newTree = treeBuilder.create();

        deployUseCase.getSteps().set(0, new Step("x", "1", "Create content", new Status(StatusType.SUCCESS, "Complete")));
        deployUseCase.getSteps().set(1, new Step("x", "2", "Push", new Status(StatusType.WARNING, "Running")));

        // 4. Crear el Commit
        GHCommit commit = repository.createCommit()
                .message("Release " + versionTag + " auto-generated")
                .tree(newTree.getSha())
                .parent(baseTreeSha)
                .create();

        // 5. Actualizar la rama para que apunte al nuevo commit
        repository.getRef("heads/main").updateTo(commit.getSHA1());

        System.out.println("✅ Versión " + versionTag + " publicada en GitHub: " + commit.getSHA1());
    }

    public void publishAndVerify(String versionTag, Path localPath, DeployUseCase deployUseCase) throws IOException {
        // 1. Publicar en GitHub (Código anterior...)
        deployUseCase.getSteps().set(0, new Step("x", "1", "Create content", new Status(StatusType.WARNING, "Running")));
        publishToGitHub(versionTag, localPath, deployUseCase);
        deployUseCase.getSteps().set(1, new Step("x", "2", "Push", new Status(StatusType.SUCCESS, "Complete")));

        deployUseCase.getSteps().set(2, new Step("x", "3", "Verify deployment", new Status(StatusType.WARNING, "Running")));
        // 2. Verificación en Cloudflare (Polling)
        int maxAttempts = 12; // 2 minutos máximo (12 * 10s)
        //String checkUrl = "https://riu-com-copy.miguelperezcolom.workers.dev/?force_version=" + versionTag;
        String checkUrl = "https://riu-com-copy.miguelperezcolom.workers.dev";

        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(10000); // Esperar 10 segundos

                System.out.println("Probando " + checkUrl);

                deployUseCase.getMessages().add(0, new Message("x", "" + deployUseCase.getMessages().size(), LocalDateTime.now(),
                        "Checking version (try " + (i + 1) + ")."));

                // Sintaxis correcta para RestClient
                var response = restClient.get()
                        .uri(checkUrl)
                        // Spring tiene una utilidad para evitar hacer el Base64 a mano:
                        .headers(headers -> headers.setBasicAuth("test", "test"))
                        .retrieve()
                        .toEntity(String.class);

                String resolvedVersion = response.getHeaders().getFirst("x-version");

                if (("v" + versionTag).equals(resolvedVersion)) {
                    deployUseCase.getMessages().add(0, new Message("x", "" + deployUseCase.getMessages().size(), LocalDateTime.now(),
                            "Version released."));
                    System.out.println("✅ Verificado: La versión " + versionTag + " ya está en vivo.");
                    deployUseCase.getSteps().set(2, new Step("x", "3", "Verify deployment", new Status(StatusType.SUCCESS, "Complete")));
                    return;
                }
                System.out.println("⏳ Esperando despliegue... (Intento " + (i + 1) + ")");

            } catch (Exception e) {
                // RestClient lanza excepciones específicas para 404, pero aquí las capturamos
                // para seguir reintentando mientras Cloudflare propaga el build.
                System.out.println("⏳ Cloudflare aún no responde o está propagando... " + e.getMessage());
            }
        }
        deployUseCase.getSteps().set(2, new Step("x", "3", "Verify deployment", new Status(StatusType.DANGER, "Error")));
        throw new RuntimeException("Timeout: GitHub aceptó el cambio, pero Cloudflare no desplegó a tiempo.");
    }
}