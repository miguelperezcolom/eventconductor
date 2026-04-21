package io.mateu.workflow.controlplaneservice.infra.out.r2;

import io.mateu.workflow.controlplaneservice.infra.out.github.CloudFlareVerifierService;
import io.mateu.workflow.controlplaneservice.infra.out.persistence.*;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.kohsuke.github.GHTreeBuilder;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Primary
@Slf4j
public class R2ReleaseFolderPublisherService {

    final ResourceEntityRepository resourceEntityRepository;
    final CloudFlareVerifierService publisher;
    final StreamBridge streamBridge;
    final ReleaseEntityRepository releaseEntityRepository;
    final SiteEntityRepository siteEntityRepository;
    private final PageEntityRepository pageEntityRepository;
    private final LanguageEntityRepository languageEntityRepository;

    public void publishReleaseFolderToGitHub(String versionTag, String stepExecutionId) throws IOException {
        // 1. Conectar con GitHub
        var client = R2Client.getClient();

        String bucketName = "riu-assets";

        var resourcesCount = resourceEntityRepository.count();

        for (int i = 0; i < resourcesCount; i++) {
            resourceEntityRepository.findAll(Pageable.ofSize(1).withPage(i)).forEach(resource -> {
                try {
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

                    var path = resource.getPath();
                    if (path.startsWith("/")) path = path.substring(1);
                    upload(path, html, versionTag, client, bucketName, stepExecutionId);

                } catch (IOException e) {
                    throw new RuntimeException("Error leyendo archivo para R2", e);
                }
            });
        }

        // Leer contenido directamente
        var siteId = releaseEntityRepository.findById(Long.valueOf(versionTag)).orElseThrow().getSiteId();
        String llmsTxt = siteEntityRepository.findById(siteId).orElseThrow().getLlmsTxt();
        upload("llms.txt", llmsTxt, versionTag, client, bucketName, stepExecutionId);

        createSitemap(stepExecutionId, siteId, versionTag, client, bucketName);

            streamBridge.send("upstream", new TaskLogEmitted(
                stepExecutionId,
                MessageType.Info,
                "✅ Versión " + versionTag + " publicada en R2"));
    }

    private void upload(String fileName, String content, String versionTag, S3Client client, String bucketName, String taskExecutionId) {
        // Publicar
        String relativePath = "v" + versionTag + "/" + fileName;

        String key = relativePath;

        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(fileName.endsWith("html")?"text/html":(fileName.endsWith("xml")?"text/xml":"text/plain")) // Importante para que el navegador lo renderice bien
                .build();

        log.info("Uploading to R2: {}", key);
        long t0 = System.currentTimeMillis();
        client.putObject(putOb, RequestBody.fromString(content));
        log.info("Uploaded to R2 in {}ms", System.currentTimeMillis() - t0);

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "" + relativePath + " added."));

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                fileName + " uploaded."));
    }


    public void publishReleaseFolderAndVerify(String versionTag, String stepExecutionId) throws IOException {
        publishReleaseFolderToGitHub(versionTag, stepExecutionId);
    }

    @SneakyThrows
    private void createSitemap(String taskExecutionId, String siteId, String versionTag, S3Client client, String bucketName) {
        var site = siteEntityRepository.findById(siteId).orElseThrow();
        var pages = pageEntityRepository.findBySiteId(site.getId());
        var languages = languageEntityRepository.findAll();

        var ns = Namespace.getNamespace("http://www.sitemaps.org/schemas/sitemap/0.9");
        Element root = new Element("sitemapindex", ns);
        Document doc = new Document(root);

        languages.forEach(l -> {
            Element sitemap = new Element("sitemap");
            sitemap.addContent(new Element("loc").setText("https://www.ejemplo.com/sitemap-" + l.getCode() + ".xml"));
            sitemap.addContent(new Element("lastmod").setText(formatUTC(LocalDateTime.now())));
            root.addContent(sitemap);

            createLanguageSitemap(taskExecutionId, siteId, l.getCode(), versionTag, client, bucketName);
        });

        XMLOutputter xmlOutput = new XMLOutputter();
        xmlOutput.setFormat(Format.getPrettyFormat());

        StringWriter sw = new StringWriter();
        xmlOutput.output(doc, sw);

        upload("sitemap.xml", sw.toString(), versionTag, client, bucketName, taskExecutionId);

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "sitemap.xml added."));
    }

    private String formatUTC(LocalDateTime now) {
        LocalDateTime ahora = LocalDateTime.now();

        // 1. Convertir LocalDateTime a OffsetDateTime (Asumiendo UTC)
        OffsetDateTime offsetDT = ahora.atOffset(ZoneOffset.UTC);

        // 2. Formatear usando el formateador estándar ISO_OFFSET_DATE_TIME
        String resultado = offsetDT.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        System.out.println(resultado);
        // Salida: 2024-05-21T12:00:00+00:00 (dependiendo de la hora actual)

        return resultado;
    }

    @SneakyThrows
    private void createLanguageSitemap(String taskExecutionId, String siteId, String languageCode, String versionTag, S3Client client, String bucketName) {
        var site = siteEntityRepository.findById(siteId).orElseThrow();
        var pages = pageEntityRepository.findBySiteId(site.getId());

        var ns = Namespace.getNamespace("http://www.sitemaps.org/schemas/sitemap/0.9");
        Element root = new Element("urlset", ns);
        Document doc = new Document(root);

        pages.stream().filter(PageEntity::isDependsOnLanguage).forEach(p -> {
            Element sitemap = new Element("sitemap");
            sitemap.addContent(new Element("url").setText(site.getUrl() + "/" + languageCode + p.getPath()));
            sitemap.addContent(new Element("lastmod").setText(formatUTC(p.getLastModification())));//.setText("2024-01-15"));
            sitemap.addContent(new Element("changefreq").setText(p.getChangeFrequency()));
            sitemap.addContent(new Element("priority").setText("" + p.getPriority()));
            root.addContent(sitemap);
        });

        XMLOutputter xmlOutput = new XMLOutputter();
        xmlOutput.setFormat(Format.getPrettyFormat());

        StringWriter sw = new StringWriter();
        xmlOutput.output(doc, sw);

        // Publicar
        upload("sitemap-" + languageCode + ".xml", sw.toString(), versionTag, client, bucketName, taskExecutionId);

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "sitemap-" + languageCode + ".xml uploaded."));
    }

}