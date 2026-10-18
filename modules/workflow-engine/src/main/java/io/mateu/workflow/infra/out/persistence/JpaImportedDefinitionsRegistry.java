package io.mateu.workflow.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Import provenance in the database: which ids each source (a git repository, a directory) brought
 * in on its last import, per namespace — workflows, and the forms and rules of an app that runs
 * their engines beside this one.
 *
 * <p>The in-memory registry forgot it on every restart, so the first import of a source after one
 * pruned nothing: a definition removed from the repository while the engine was down stayed ACTIVE,
 * its schedules still firing, until some later import happened to see it go. Kept here, the first
 * import after a restart prunes exactly as any other does.
 *
 * <p>{@link Primary} rather than a conditional on the in-memory bean: those are declared by the
 * workflow, forms and rules engines' configurations, whichever is processed first, and an
 * order-dependent condition is the wrong thing to hang durability on.
 */
@Service
@Primary
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaImportedDefinitionsRegistry implements ImportedDefinitionsRegistry {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    final ImportedDefinitionSourceEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Set<String> idsFor(String namespace, String repositoryUrl) {
        return repository.findById(id(namespace, repositoryUrl))
                .map(row -> fromJson(row.getIdsJson()))
                .orElse(Set.of());
    }

    @Override
    @Transactional
    public void replace(String namespace, String repositoryUrl, Set<String> ids) {
        var id = id(namespace, repositoryUrl);
        if (ids == null || ids.isEmpty()) {
            repository.deleteById(id);
            return;
        }
        repository.save(new ImportedDefinitionSourceEntity(id, namespace, repositoryUrl, toJson(ids)));
    }

    static String id(String namespace, String source) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + "#" + source).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toJson(Set<String> ids) {
        try {
            return JSON.writeValueAsString(ids.stream().sorted().toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(new LinkedHashSet<>(JSON.readValue(json, new TypeReference<java.util.List<String>>() {
            })));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unreadable import provenance: " + json, e);
        }
    }
}
