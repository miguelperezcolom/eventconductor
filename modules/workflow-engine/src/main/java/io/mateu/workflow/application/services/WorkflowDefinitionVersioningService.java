package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntity;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;

import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Owns the engine-assigned version number and the append-only version history of a definition.
 *
 * <p>Called from the single JPA write path ({@code WorkflowDefinitionDBRepository.save}) for every
 * definition save. A new immutable version is recorded <b>only when the content changes</b>: the
 * decision is made by hashing the version-relevant content (steps + config), deliberately excluding
 * the runtime flags ({@code paused/disabled/archived}) and the {@code version} field itself. So a
 * lifecycle toggle or the git-import prune — which change only those excluded fields — produce the
 * same hash as the current head and record nothing; and re-importing an unchanged file on every boot
 * is idempotent. Only a real content change bumps the version and appends a snapshot.
 *
 * <p>JPA-only: version history is durable state, and the whole versioning feature is scoped to
 * {@code workflow.persistence=jpa}.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class WorkflowDefinitionVersioningService {

    private final WorkflowDefinitionVersionEntityRepository versions;

    /**
     * Returns the version number the head row should carry, recording a new immutable version iff the
     * content hash differs from the latest recorded one. No-op (returns the existing version) when the
     * content is unchanged.
     */
    public int reconcile(WorkflowDefinition definition) {
        String hash = contentHash(definition);
        var latest = versions.findTopByDefinitionIdOrderByVersionDesc(definition.id());
        if (latest.isPresent() && hash.equals(latest.get().getContentHash())) {
            return latest.get().getVersion();
        }
        int next = latest.map(v -> v.getVersion() + 1).orElse(1);
        var stamped = definition.withVersion(next);
        try {
            versions.save(new WorkflowDefinitionVersionEntity(
                    definition.id() + ":" + next,
                    definition.id(),
                    next,
                    definition.name(),
                    toJson(stamped),
                    hash,
                    LocalDateTime.now()));
            log.info("Recorded workflow definition '{}' version {} (content changed)", definition.id(), next);
            return next;
        } catch (DataIntegrityViolationException race) {
            // Another writer recorded this version first (concurrent boot/import): adopt its number.
            return versions.findTopByDefinitionIdOrderByVersionDesc(definition.id())
                    .map(WorkflowDefinitionVersionEntity::getVersion)
                    .orElse(next);
        }
    }

    /**
     * SHA-256 of a canonical projection of the version-relevant content. Steps are sorted by id and
     * the fields are written in a fixed order so the same content always hashes the same, regardless
     * of step declaration order or map iteration order — otherwise a re-import would spuriously bump.
     * Excludes {@code version} and the runtime flags by construction.
     */
    String contentHash(WorkflowDefinition d) {
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("name", d.name());
        canonical.put("description", d.description());
        canonical.put("cronExpression", d.cronExpression());
        canonical.put("limitConcurrentExecutions", d.limitConcurrentExecutions());
        canonical.put("maxConcurrentExecutions", d.maxConcurrentExecutions());
        canonical.put("enqueueOnLimit", d.enqueueOnLimit());
        canonical.put("defaultMaxStepExecutions", d.defaultMaxStepExecutions());
        canonical.put("steps", d.steps().stream()
                .sorted(Comparator.comparing(Step::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(io.mateu.core.infra.JsonSerializer::toJson)
                .toList());
        return sha256(toJson(canonical));
    }

    private static String sha256(String s) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is guaranteed present on every JVM
        }
    }
}
