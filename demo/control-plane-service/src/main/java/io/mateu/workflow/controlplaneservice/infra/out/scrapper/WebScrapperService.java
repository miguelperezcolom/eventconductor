package io.mateu.workflow.controlplaneservice.infra.out.scrapper;

import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceContent;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourcePath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebScrapperService {

    private final ResourceRepository repository;
    private final RestClient restClient;

    public void scrape(String url, String countryCode, String versionTag) {
        try {
            log.info("Iniciando scrape completo de: {} (Versión: {})", url, versionTag);

            // 1. Descargar el HTML base
            byte[] htmlContent = download(url, countryCode);
            if (htmlContent == null) return;

            // 2. Guardar el HTML principal
            String mainPath = extractPathFromUrl(url);
            save(mainPath, htmlContent, versionTag);

            // 3. Parsear el HTML para buscar recursos (JS, CSS, Imágenes)
            String htmlString = new String(htmlContent, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(htmlString, url);

            Set<String> resourcesToDownload = new HashSet<>();

            // Seleccionamos SRC de scripts e imágenes, y HREF de links CSS
            doc.select("script[src]").forEach(el -> resourcesToDownload.add(el.absUrl("src")));
            doc.select("img[src]").forEach(el -> resourcesToDownload.add(el.absUrl("src")));
            doc.select("link[rel=stylesheet]").forEach(el -> resourcesToDownload.add(el.absUrl("href")));

            log.info("Encontrados {} recursos relacionados.", resourcesToDownload.size());

            // 4. Descargar cada recurso relacionado
            for (String resUrl : resourcesToDownload) {
                if (resUrl == null || resUrl.isBlank()) continue;

                try {
                    byte[] resData = download(resUrl, countryCode);
                    if (resData != null) {
                        save(extractPathFromUrl(resUrl), resData, versionTag);
                    }
                } catch (Exception e) {
                    log.warn("Saltando recurso fallido: {} - {}", resUrl, e.getMessage());
                }
            }
            log.info("✅ Proceso de scrape finalizado para {}", url);

        } catch (Exception e) {
            log.error("Error crítico en el proceso de scrape: {}", e.getMessage());
        }
    }

    private byte[] download(String url, String countryCode) {
        try {
            return restClient.get()
                    .uri(URI.create(url))
                    .header("Cookie", "X-RIU-GP=" + countryCode)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            log.debug("Error al descargar {}: {}", url, e.getMessage());
            return null;
        }
    }

    public void save(String path, byte[] content, String versionTag) {
        var id = path + "-" + versionTag;
        // Uso de los Value Objects según tu estructura
        repository.save(Resource.of(
                new ResourceId(id),
                new ResourceName(extractFileNameFromPath(path)),
                new ResourcePath(path),
                new ResourceContent(content)
        ));
    }

    private String extractFileNameFromPath(String path) {
        if (path == null || path.isBlank()) return "unknown";

        // Obtenemos la parte final después de la última barra
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1) return path;

        String fileName = path.substring(lastSlash + 1);
        return fileName.isBlank() ? "index.html" : fileName;
    }

    private String extractPathFromUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();

            if (path == null || path.isEmpty() || path.equals("/")) {
                return "index.html";
            }

            // Eliminamos la barra inicial si existe
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            // Fallback: limpiar caracteres no válidos para sistema de archivos
            return url.replaceAll("https?://", "").replaceAll("[^a-zA-Z0-9./]", "_");
        }
    }
}