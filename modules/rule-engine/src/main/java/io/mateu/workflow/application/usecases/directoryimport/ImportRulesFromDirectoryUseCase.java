package io.mateu.workflow.application.usecases.directoryimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.imports.DerivedIds;
import io.mateu.workflow.infra.config.RuleDirectoryImportProperties;
import io.mateu.workflow.webhook.ImportedDefinitionsRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Imports rule definitions from directories on the local filesystem.
 *
 * <p>It is also where importing rules <em>lives</em>: the git import is a clone followed by exactly
 * this, and calls {@link #importFrom} to do it — the same split workflows and forms have had all
 * along. Rules did not, and the cost was not theoretical: the git import carried its own
 * {@code Files.walk}, its own file filter and its own parser, so the one place that decides what a
 * rule file is drifted away from the two that agreed. {@code .ecrule} ended up declared in
 * {@link DerivedIds} and in the Maven plugin's copy of that list, and readable by neither the
 * engine nor either IDE plugin — a build could validate a file the engine would then not load.
 *
 * <p>Worse, both lists met inside one call: the git import handed its own three-extension filter to
 * {@code DerivedIds.declaredUnder}, which knows six. There is one filter now, and it is here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportRulesFromDirectoryUseCase {

    private static final Set<String> RULE_TYPES = Set.of("expression", "decision-table");

    /** Registry namespace so workflows, forms and rules can share one provenance store. */
    static final String NAMESPACE = "rule";

    final RuleDirectoryImportProperties directoryImportProperties;
    final SaveRuleUseCase saveRuleUseCase;
    final io.mateu.workflow.application.services.RuleValidator ruleValidator;
    final RuleRepository ruleRepository;
    final RuleCatalogMetrics ruleCatalogMetrics;
    final ImportedDefinitionsRegistry importedDefinitionsRegistry;

    // Own mappers: headless embedders may not expose an ObjectMapper bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /** Re-imports every configured directory. */
    public ImportRulesResult handle() {
        return handle(directoryImportProperties.getDirectories());
    }

    /** Re-imports the given directories. */
    public ImportRulesResult handle(List<String> directories) {
        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var pruned = new ArrayList<String>();

        for (var directory : directories) {
            try {
                var root = Path.of(directory).toAbsolutePath().normalize();
                if (!Files.isDirectory(root)) {
                    throw new IOException("not a directory");
                }
                // The key is the absolute path, so two apps pointed at different directories prune
                // independently and a relative path written two ways is still one source.
                importFrom(root, root.toString(), imported, errors, pruned);
            } catch (Exception e) {
                log.error("Failed to import rules from directory {}: {}", directory, e.getMessage(), e);
                errors.add("Directory " + directory + ": " + e.getMessage());
            }
        }

        ruleCatalogMetrics.rulesImported(imported.size());
        return new ImportRulesResult(imported, errors, pruned);
    }

    /**
     * Imports every rule under {@code root} and prunes what this source imported before and no
     * longer has. {@code pruneKey} identifies the source in the provenance registry — a repository
     * url for a clone, the directory itself for a directory.
     */
    public void importFrom(Path root, String pruneKey, List<String> imported, List<String> errors,
                           List<String> pruned) throws IOException {
        var importedIds = new LinkedHashSet<String>();
        scanAndImport(root, imported, errors, importedIds);
        pruneRemovedRules(pruneKey, importedIds, pruned);
    }

    /**
     * What a rule file is called.
     *
     * <p>{@code .ecrule} alongside the three generic ones, which is what the other two engines do
     * with their own extension. It is the file's <em>content</em> that decides whether it is a rule
     * — a {@code name} and a known {@code type} — so a repository holding workflows next to rules
     * is harmless whatever the files are called.
     */
    static boolean isDefinitionFile(Path path) {
        String name = path.toString().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".ecrule");
    }

    public void scanAndImport(Path repoRoot, List<String> imported, List<String> errors,
                              Set<String> importedIds) throws IOException {
        var declaredIds = DerivedIds.declaredUnder(repoRoot,
                ImportRulesFromDirectoryUseCase::isDefinitionFile, this::readTree);
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(ImportRulesFromDirectoryUseCase::isDefinitionFile)
                    .forEach(file -> {
                        try {
                            importDefinitionFile(file, repoRoot, imported, importedIds, declaredIds);
                        } catch (Exception e) {
                            log.warn("Skipping {}: {}", file, e.getMessage());
                            errors.add("File " + repoRoot.relativize(file) + ": " + e.getMessage());
                        }
                    });
        }
    }

    /** Everything but {@code .json} goes to the YAML parser, which reads JSON too. */
    private JsonNode readTree(Path file) throws IOException {
        return file.toString().toLowerCase().endsWith(".json")
                ? OBJECT_MAPPER.readTree(file.toFile())
                : YAML_MAPPER.readTree(file.toFile());
    }

    private void importDefinitionFile(Path file, Path repoRoot, List<String> imported,
                                      Set<String> importedIds, Set<String> declaredIds)
            throws IOException {
        var node = readTree(file);

        // Quick pre-check: must have "name" and a rule "type" to be a rule definition.
        if (!node.has("name") || !node.has("type") || !RULE_TYPES.contains(node.get("type").asText())) {
            return;
        }

        // The document as written, before binding drops whatever the record has no field for —
        // the only moment a misspelled key is still visible. See RuleValidator.validateSource.
        ruleValidator.validateSource(node, file.toString());

        var rule = OBJECT_MAPPER.treeToValue(node, Rule.class);

        boolean hadExplicitId = rule.id() != null && !rule.id().isBlank();
        if (!hadExplicitId) {
            // The save use case would generate a fresh id, which is the same as having none: the
            // next import could not find what this one created. The file's path gives it one that
            // is the same next time.
            var derivedId = DerivedIds.forFile(repoRoot, file);
            DerivedIds.refuseIfTaken(derivedId, declaredIds, importedIds);
            rule = withId(rule, derivedId);
        }

        // Validation and publication happen inside the save use case.
        var id = saveRuleUseCase.handle(new SaveRuleCommand(rule));
        // Every rule is prune-tracked: an id derived from the path can be reconciled on a later
        // import, which a generated one never could.
        importedIds.add(id);
        log.info("Imported rule '{}' (id={}) from {}", rule.name(), id, repoRoot.relativize(file));
        imported.add(rule.name() + " [" + id + "]");
    }

    private static Rule withId(Rule rule, String id) {
        return new Rule(id, rule.name(), rule.description(), rule.type(), rule.version(),
                rule.salience(), rule.tags(), rule.when(), rule.then(),
                rule.inputs(), rule.outputs(), rule.rows(), rule.hitPolicy());
    }

    /**
     * Deletes rules previously imported from this source that are no longer present. Scoped by the
     * registry to imported rules, so classpath and hand-authored rules are never touched. Rules have
     * no lifecycle status, so pruning removes them (unlike workflow definitions, which are archived).
     */
    public void pruneRemovedRules(String source, Set<String> importedIds, List<String> pruned) {
        var previous = importedDefinitionsRegistry.idsFor(NAMESPACE, source);
        for (var id : previous) {
            if (importedIds.contains(id)) {
                continue;
            }
            ruleRepository.findById(id).ifPresent(rule -> {
                ruleRepository.deleteAllById(List.of(id));
                log.info("Pruned (deleted) rule '{}' (id={}) — no longer in {}", rule.name(), id, source);
                pruned.add(rule.name() + " [" + id + "]");
            });
        }
        importedDefinitionsRegistry.replace(NAMESPACE, source, importedIds);
    }

    public record ImportRulesResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
