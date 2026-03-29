package io.mateu.workflow.controlplaneservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Descarga todos los assets de riu.com registrados en un fichero HAR,
 * independientemente del status que devolvieron en el worker mirror.
 *
 * Las URLs del worker (riu-com-copy.miguelperezcolom.workers.dev)
 * se reescriben a www.riu.com antes de descargar.
 * Las URLs de terceros (Google, TikTok, Amazon...) se ignoran.
 *
 * Uso:
 *   java HarAssetDownloader <fichero.har> [directorio-salida]
 *
 * Dependencia (añadir al classpath):
 *   com.fasterxml.jackson.core:jackson-databind:2.17.x
 */
public class HarAssetDownloader {

    // ── Configuración ─────────────────────────────────────────────────────────

    private static final String WORKER_BASE  = "https://riu-com-copy.miguelperezcolom.workers.dev";
    private static final String ORIGIN_BASE  = "https://www.riu.com";

    private static final int      CONCURRENCY = 8;
    private static final Duration TIMEOUT     = Duration.ofSeconds(30);
    private static final int      MAX_RETRIES = 3;

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        Path harFile   = Path.of("/Users/mguel/Downloads/www.riu.com.har");
        Path outputDir = Path.of("/Users/mguel/IdeaProjects/eventconductor/.dev/downloaded_assets");

        System.out.println("HAR       : " + harFile.toAbsolutePath());
        System.out.println("Salida    : " + outputDir.toAbsolutePath());

        List<String> paths = extractRiuPaths(harFile);
        System.out.printf("URLs únicas de riu.com en el HAR: %d%n%n", paths.size());

        Files.createDirectories(outputDir);
        downloadAll(paths, outputDir);
    }

    // ── Paso 1: extraer paths de riu.com del HAR ──────────────────────────────

    /**
     * Recorre todas las entradas del HAR y devuelve los paths únicos
     * de las URLs que pertenecen al worker mirror (y por tanto a riu.com).
     * Ignora entradas con status 0 (canceladas/bloqueadas por el navegador).
     */
    static List<String> extractRiuPaths(Path harFile) throws IOException {
        ObjectMapper mapper  = new ObjectMapper();
        JsonNode     entries = mapper.readTree(harFile.toFile()).path("log").path("entries");

        Set<String> paths = new LinkedHashSet<>();
        for (JsonNode entry : entries) {
            int    status = entry.path("response").path("status").asInt(0);
            String url    = entry.path("request").path("url").asText();

            if (status == 0) continue;                        // cancelada, sin respuesta
            if (!url.startsWith(ORIGIN_BASE)) continue;       // es de un tercero, ignorar

            paths.add(url.substring(ORIGIN_BASE.length()));   // guarda el path (/home/...)
        }
        return new ArrayList<>(paths);
    }

    // ── Paso 2: descargar ─────────────────────────────────────────────────────

    static void downloadAll(List<String> paths, Path outputDir) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        ExecutorService pool   = Executors.newFixedThreadPool(CONCURRENCY);
        AtomicInteger   ok     = new AtomicInteger();
        AtomicInteger   failed = new AtomicInteger();

        for (String path : paths) {
            pool.submit(() -> {
                String originUrl = ORIGIN_BASE + path;
                Path   dest      = outputDir.resolve(toLocalPath(path));

                if (downloadWithRetry(client, originUrl, dest)) {
                    System.out.printf("[OK]   %s%n", path);
                    ok.incrementAndGet();
                } else {
                    System.out.printf("[FAIL] %s%n", path);
                    failed.incrementAndGet();
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
        System.out.printf("%n--- Completado: %d descargados, %d fallidos ---%n", ok.get(), failed.get());
    }

    // ── HTTP con reintentos ───────────────────────────────────────────────────

    static boolean downloadWithRetry(HttpClient client, String url, Path dest) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0 (compatible; HarAssetDownloader/1.0)")
                        .GET()
                        .build();

                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();
                if (status == 200) {
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = response.body()) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return true;
                }

                // 404 real en origen: no tiene sentido reintentar
                if (status == 404) {
                    System.out.printf("[404-ORIGIN] %s%n", url);
                    return false;
                }

                System.out.printf("[HTTP-%d] %s (intento %d/%d)%n", status, url, attempt, MAX_RETRIES);

            } catch (Exception e) {
                System.out.printf("[ERROR] %s – %s (intento %d/%d)%n", url, e.getMessage(), attempt, MAX_RETRIES);
            }

            try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Convierte un path de URL en una ruta local relativa legible,
     * decodificando el percent-encoding (p.ej. %20 → espacio).
     */
    static String toLocalPath(String urlPath) {
        try {
            String decoded = java.net.URLDecoder.decode(urlPath, java.nio.charset.StandardCharsets.UTF_8);
            return decoded.startsWith("/") ? decoded.substring(1) : decoded;
        } catch (Exception e) {
            return urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
        }
    }
}