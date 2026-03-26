package io.mateu.workflow.controlplaneservice.infra.out.github;

import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class GitHubPublisherService {

    @Autowired
    private RestClient restClient; // Necesitarás definir este Bean en tu configuración

    @Value("${github.token}")
    private String githubToken;

    @Value("${github.repo}")
    private String repoName; // Ej: "usuario/eventconductor"

    @Value("classpath:github/wrangler.jsonc")
    private Resource wranglerResource;

    public void publishToGitHub(String versionTag, Path localPath) throws IOException {
        // 1. Conectar con GitHub
        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();
        GHRepository repository = github.getRepository(repoName);

        // 2. Obtener el SHA del último commit de la rama principal (master/main)
        String baseTreeSha = repository.getRef("heads/main").getObject().getSha();

        // 3. Crear el "Tree" con los archivos locales
        GHTreeBuilder treeBuilder = repository.createTree().baseTree(baseTreeSha);

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

        // Leer contenido directamente
        String wranglerJson = wranglerResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        wranglerJson = wranglerJson.replace("v0000000002", versionTag);
        // Publicar
        treeBuilder.add("wrangler.jsonc", wranglerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);

        GHTree newTree = treeBuilder.create();

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

    public void publishAndVerify(String versionTag, Path localPath) throws IOException {
        // 1. Publicar en GitHub (Código anterior...)
        publishToGitHub(versionTag, localPath);

        // 2. Verificación en Cloudflare (Polling)
        int maxAttempts = 12; // 2 minutos máximo (12 * 10s)
        //String checkUrl = "https://riu-com-copy.miguelperezcolom.workers.dev/?force_version=" + versionTag;
        String checkUrl = "https://riu-com-copy.miguelperezcolom.workers.dev";

        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(10000); // Esperar 10 segundos

                System.out.println("Probando " + checkUrl);

                // Sintaxis correcta para RestClient
                var response = restClient.get()
                        .uri(checkUrl)
                        .retrieve()
                        .toEntity(String.class);

                String resolvedVersion = response.getHeaders().getFirst("x-resolved-version");

                if (versionTag.equals(resolvedVersion)) {
                    System.out.println("✅ Verificado: La versión " + versionTag + " ya está en vivo.");
                    return;
                }
                System.out.println("⏳ Esperando despliegue... (Intento " + (i + 1) + ")");

            } catch (Exception e) {
                // RestClient lanza excepciones específicas para 404, pero aquí las capturamos
                // para seguir reintentando mientras Cloudflare propaga el build.
                System.out.println("⏳ Cloudflare aún no responde o está propagando... " + e.getMessage());
            }
        }
        throw new RuntimeException("Timeout: GitHub aceptó el cambio, pero Cloudflare no desplegó a tiempo.");
    }
}