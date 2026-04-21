package io.mateu.workflow.controlplaneservice.infra.out.github;

import io.mateu.workflow.controlplaneservice.infra.out.persistence.*;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.fromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class GitHubReleaseSettingPublisherService {

    final ReleaseEntityRepository releaseEntityRepository;
    final SiteEntityRepository siteEntityRepository;
    final CloudFlareVerifierService verifierService;
    final RouteEntityRepository routeEntityRepository;
    final StreamBridge streamBridge;
    private final PageEntityRepository pageEntityRepository;
    private final LanguageEntityRepository languageEntityRepository;

    @Value("${github.token}")
    private String githubToken;

    @Value("${github.repo}")
    private String repoName; // Ej: "usuario/eventconductor"

    @Value("classpath:github/wrangler.jsonc")
    private Resource wranglerResource;

    public void setReleaseAndPublishToGitHub(String taskExecutionId, String deploymentId, String versionTag) throws IOException {
        // 1. Conectar con GitHub
        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();
        GHRepository repository = github.getRepository(repoName);

        // 2. Obtener el SHA del último commit de la rama principal (master/main)
        String baseTreeSha = repository.getRef("heads/main").getObject().getSha();

        // 3. Crear el "Tree" con los archivos locales
        GHTreeBuilder treeBuilder = repository.createTree().baseTree(baseTreeSha);

        Map<String, String> releaseMap = new HashMap<>();
        routeEntityRepository.findAll()
                .stream().filter(r -> r.getPlannedReleaseId() != null)
                .forEach(r -> releaseMap.put(r.getPath(), "v" + r.getPlannedReleaseId()));

        // Leer contenido directamente
        Map<String, Object> wrangler = fromJson(wranglerResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> vars = (Map<String, Object>) wrangler.get("vars");
        vars.put("DEPLOYMENT_ID", deploymentId);
        vars.put("DEFAULT_VERSION", "v" + versionTag);
        vars.put("ROUTE_VERSIONS_JSON", toJson(releaseMap));
        // Publicar
        treeBuilder.add("wrangler.jsonc", toJson(wrangler).getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "wrangler.jsonc updated."));


//"ROUTE_VERSIONS_JSON": "{\"/es/\": \"v21\", \"/en/\": \"v22\", \"/promo/\": \"v_especial\"}"


        GHTree newTree = treeBuilder.create();

        // 4. Crear el Commit
        GHCommit commit = repository.createCommit()
                .message("Release " + versionTag + " auto-generated")
                .tree(newTree.getSha())
                .parent(baseTreeSha)
                .create();

        // 5. Actualizar la rama para que apunte al nuevo commit
        repository.getRef("heads/main").updateTo(commit.getSHA1());

        streamBridge.send("upstream", new TaskLogEmitted(
                taskExecutionId,
                MessageType.Info,
                "✅ Versión " + versionTag + " publicada en GitHub: " + commit.getSHA1()));
    }

}