package io.mateu.workflow.application.usecases.directoryimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.imports.DerivedIds;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
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
 * Imports form definitions from directories on the local filesystem.
 *
 * <p>It is also where importing a directory <em>lives</em>: the git import is a clone followed by
 * exactly this, and calls {@link #importFrom} to do it. Git import reads what is committed, which
 * is right for a deployment and wrong for the loop where someone is writing a form — edit, commit,
 * restart, discover the commit was the step you forgot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportFormsFromDirectoryUseCase {

    /** Registry namespace so workflows, forms and rules can share one provenance store. */
    static final String NAMESPACE = "form";

    final DirectoryImportProperties directoryImportProperties;
    final FormRepository formRepository;
    final FormsMetrics formsMetrics;
    final ImportedDefinitionsRegistry importedDefinitionsRegistry;
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /** Re-imports every configured directory. */
    public ImportFormsResult handle() {
        return handle(directoryImportProperties.getDirectories());
    }

    /** Re-imports the given directories. */
    public ImportFormsResult handle(List<String> directories) {
        var imported = new ArrayList<String>();
        var errors   = new ArrayList<String>();
        var pruned   = new ArrayList<String>();

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
                log.error("Failed to import from directory {}: {}", directory, e.getMessage(), e);
                errors.add("Directory " + directory + ": " + e.getMessage());
            }
        }

        formsMetrics.formsImported(imported.size());

        return new ImportFormsResult(imported, errors, pruned);
    }

    /**
     * Imports every form under {@code root} and prunes what this source imported before and no
     * longer has. {@code pruneKey} identifies the source in the provenance registry — a repository
     * url for a clone, the directory itself for a directory.
     */
    public void importFrom(Path root, String pruneKey, List<String> imported, List<String> errors,
                           List<String> pruned) throws IOException {
        var importedIds = new LinkedHashSet<String>();
        scanAndImport(root, imported, errors, importedIds);
        pruneRemovedForms(pruneKey, importedIds, pruned);
    }

    /**
     * The files the scan picks up. {@code .ecform} is the extension both IDE plugins register for a
     * form — it is what the visual editor saves, and what the schema follows the user into — and it
     * was the one the scan did not look at, so a form authored that way was never imported and
     * never said why. Its workflow twin has always accepted {@code .ec} for the same reason.
     */
    private static boolean isDefinitionFile(Path path) {
        String name = path.toString();
        return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".ecform");
    }

    public void scanAndImport(Path repoRoot, List<String> imported, List<String> errors,
                               Set<String> importedIds) throws IOException {
        var declaredIds = DerivedIds.declaredUnder(repoRoot,
                ImportFormsFromDirectoryUseCase::isDefinitionFile, this::readTree);
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(ImportFormsFromDirectoryUseCase::isDefinitionFile)
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

    /**
     * .ecform goes to the YAML parser, which reads JSON too — the plugins register it as YAML (a
     * JSON superset) and a form saved as either parses the same way.
     */
    private com.fasterxml.jackson.databind.JsonNode readTree(Path file) throws IOException {
        var fileName = file.toString();
        return (fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".ecform"))
                ? YAML_MAPPER.readTree(file.toFile())
                : objectMapper.readTree(file.toFile());
    }

    private void importDefinitionFile(Path file, Path repoRoot, List<String> imported,
                                      Set<String> importedIds, Set<String> declaredIds) throws IOException {
        var node = readTree(file);

        // Quick pre-check: must have "name" and "fields" to be a form definition.
        if (!node.has("name") || !node.has("fields")) {
            return;
        }

        var form = objectMapper.treeToValue(node, Form.class);

        boolean hadExplicitId = form.id() != null && !form.id().isBlank();
        if (!hadExplicitId) {
            // Derived from the file's path, so the next import of this file updates the form it
            // created last time rather than adding another one beside it.
            var derivedId = DerivedIds.forFile(repoRoot, file);
            DerivedIds.refuseIfTaken(derivedId, declaredIds, importedIds);
            form = new Form(derivedId, form.name(), form.description(), form.fields());
        }

        // Validation (schema + invariants) is handled inside formRepository.save().
        formRepository.save(form);
        // Every form is prune-tracked now: a path-derived id is as reconcilable as a declared one,
        // and pruning is precisely what the old generated ids could not take part in.
        importedIds.add(form.id());
        log.info("Imported form '{}' (id={}) from {}", form.name(), form.id(), repoRoot.relativize(file));
        imported.add(form.name() + " [" + form.id() + "]");
    }

    /**
     * Deletes forms previously imported from this source that are no longer present. Scoped by the
     * registry to imported forms, so hand-authored forms are never touched. Forms have no lifecycle status, so pruning removes them (unlike workflow
     * definitions, which are archived); a form carries no running state, so this is safe.
     */
    public void pruneRemovedForms(String repositoryUrl, Set<String> importedIds, List<String> pruned) {
        var previous = importedDefinitionsRegistry.idsFor(NAMESPACE, repositoryUrl);
        for (var id : previous) {
            if (importedIds.contains(id)) {
                continue;
            }
            formRepository.findById(id).ifPresent(form -> {
                formRepository.deleteAllById(List.of(id));
                log.info("Pruned (deleted) form '{}' (id={}) — no longer in {}", form.name(), id, repositoryUrl);
                pruned.add(form.name() + " [" + id + "]");
            });
        }
        importedDefinitionsRegistry.replace(NAMESPACE, repositoryUrl, importedIds);
    }

    public record ImportFormsResult(List<String> imported, List<String> errors, List<String> pruned) {}
}
