package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.codegen.GeneratedJavaFile;
import io.mateu.workflow.codegen.TaskSourceGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Generates the worker types for a set of task contracts: per contract version an Input and Output
 * record, a task interface to implement (named like {@code GreetV1Task}), its declared errors as
 * {@code TaskFailure} subclasses, and a per-group {@code @AutoConfiguration} that turns an
 * implemented handler into a {@code TaskRegistration} bean the runtime collects. In a standalone
 * service it also emits the {@code @SpringBootApplication} class. Sources land under
 * {@code target/generated-sources} (added as a compile root); the {@code AutoConfiguration.imports}
 * lands under a generated resources root, so registrations are found without component-scanning.
 *
 * <p>The contracts come from, in order of precedence: a Maven artifact ({@code definitions}, a
 * versioned zip — the preferred, reproducible source), a git checkout ({@code repository} +
 * {@code ref}, pinned to a tag or commit; a branch is rejected unless {@code allowBranch}), or the
 * project's own {@code tasksDirectory}. Runs in {@code generate-sources}, before compilation.
 */
@Mojo(name = "generate-worker-sources", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final List<String> DEFINITION_EXTENSIONS =
            List.of(".ectask", ".json", ".yaml", ".yml");
    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Project directory holding task contracts, used when neither {@code definitions} nor {@code repository} is set. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/tasks")
    private File tasksDirectory;

    /** Where the generated {@code .java} sources are written (added as a compile source root). */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/eventconductor-tasks")
    private File outputDirectory;

    /** Where the generated {@code AutoConfiguration.imports} is written (added as a resource root). */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/eventconductor-tasks")
    private File resourcesDirectory;

    /** Where resolved definition bundles (Maven artifact or git) are cached and unpacked. */
    @Parameter(defaultValue = "${project.build.directory}/eventconductor-definitions")
    private File definitionsCache;

    /** The base package for the generated types; a contract's group is appended as a sub-package. */
    @Parameter(property = "eventconductor.generate.basePackage", defaultValue = "io.mateu.workflow.tasks.generated")
    private String basePackage;

    /** A definitions Maven artifact {@code groupId:artifactId:version} (resolved as a zip). */
    @Parameter(property = "eventconductor.generate.definitions")
    private String definitions;

    /** A git repository URL to take the definitions from (with {@code ref}). */
    @Parameter(property = "eventconductor.generate.repository")
    private String repository;

    /** The git tag or commit to check out. A branch is rejected unless {@code allowBranch}. */
    @Parameter(property = "eventconductor.generate.ref")
    private String ref;

    /** Allow a git branch ref (warns instead of failing). Off by default for reproducibility. */
    @Parameter(property = "eventconductor.generate.allowBranch", defaultValue = "false")
    private boolean allowBranch;

    /** Generate only these groups (comma-separated); empty means every group found. */
    @Parameter(property = "eventconductor.generate.group")
    private String group;

    /** Generate only these tasks (comma-separated {@code id} or {@code id@version}); empty means all. */
    @Parameter(property = "eventconductor.generate.tasks")
    private String tasks;

    /** When set, also generate this {@code @SpringBootApplication} class (a standalone service). */
    @Parameter(property = "eventconductor.generate.applicationClass")
    private String applicationClass;

    /** Skip generation entirely. */
    @Parameter(property = "eventconductor.generate.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("EventConductor task generation skipped (eventconductor.generate.skip=true).");
            return;
        }

        Path directory = resolveDefinitionsDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            getLog().debug("No task contracts to generate (nowhere to read them from).");
            registerRoots();
            return;
        }

        var wantedGroups = split(group);
        var wantedTasks = split(tasks);
        var contracts = new ArrayList<JsonNode>();
        for (var file : listDefinitionFiles(directory)) {
            JsonNode doc;
            try {
                doc = parse(file);
            } catch (IOException e) {
                throw new MojoExecutionException("Could not parse task contract " + file, e);
            }
            if (isSelectedContract(doc, wantedGroups, wantedTasks)) {
                contracts.add(doc);
            }
        }

        if (contracts.isEmpty()) {
            getLog().info("EventConductor: no task contracts matched; nothing generated.");
            registerRoots();
            return;
        }

        var generator = new TaskSourceGenerator(basePackage);
        var generated = generator.generate(contracts);
        var javaFiles = new ArrayList<>(generated.javaFiles());
        if (applicationClass != null && !applicationClass.isBlank()) {
            javaFiles.add(generator.applicationClass(applicationClass.trim()));
        }

        try {
            cleanTree(outputDirectory.toPath());
            cleanTree(resourcesDirectory.toPath());
            for (GeneratedJavaFile file : javaFiles) {
                var target = outputDirectory.toPath().resolve(file.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.source());
            }
            if (!generated.autoConfigurationImports().isEmpty()) {
                var imports = resourcesDirectory.toPath().resolve(IMPORTS_PATH);
                Files.createDirectories(imports.getParent());
                Files.writeString(imports, generated.autoConfigurationImports());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write generated task sources", e);
        }

        registerRoots();
        getLog().info("EventConductor: generated " + javaFiles.size()
                + " source(s) from " + contracts.size() + " task contract(s) into " + outputDirectory + ".");
    }

    /** Whether a contract document is a task contract and passes the group/task filters. */
    static boolean isSelectedContract(JsonNode doc, Set<String> groups, Set<String> tasks) {
        if (!doc.has("id") || !doc.has("version") || !doc.has("group")) {
            return false; // not a task contract; validation is the validate goal's job
        }
        if (!groups.isEmpty() && !groups.contains(doc.get("group").asText())) {
            return false;
        }
        if (tasks.isEmpty()) {
            return true;
        }
        var id = doc.get("id").asText();
        var ref = id + "@" + doc.get("version").asInt();
        return tasks.contains(id) || tasks.contains(ref);
    }

    /** The directory to read contracts from: Maven artifact, then git, then the local project. */
    private Path resolveDefinitionsDirectory() throws MojoExecutionException {
        if (definitions != null && !definitions.isBlank()) {
            return findTasksDirectory(resolveFromArtifact(definitions.trim()));
        }
        if (repository != null && !repository.isBlank()) {
            return findTasksDirectory(resolveFromGit(repository.trim(), ref == null ? "" : ref.trim()));
        }
        return tasksDirectory == null ? null : tasksDirectory.toPath();
    }

    /** Resolves a definitions Maven artifact to a zip file; overridable in tests via {@link #zipResolver}. */
    @FunctionalInterface
    interface ZipResolver {
        Path resolve(String gav) throws MojoExecutionException;
    }

    /** Set in tests to avoid a real repository; null in a real build (uses the injected resolver). */
    ZipResolver zipResolver;

    private Path resolveFromArtifact(String gav) throws MojoExecutionException {
        ZipResolver resolver = zipResolver != null ? zipResolver : this::resolveArtifactZip;
        var zip = resolver.resolve(gav);
        var target = definitionsCache.toPath().resolve("artifact");
        try {
            unzip(zip, target);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not unpack definitions artifact " + gav, e);
        }
        return target;
    }

    private Path resolveArtifactZip(String gav) throws MojoExecutionException {
        var parts = gav.split(":");
        if (parts.length < 3) {
            throw new MojoExecutionException("definitions must be groupId:artifactId:version, was: " + gav);
        }
        var artifact = new DefaultArtifact(parts[0], parts[1], "zip", parts[parts.length - 1]);
        try {
            var request = new ArtifactRequest(artifact, remoteRepositories, null);
            var result = repositorySystem.resolveArtifact(repositorySession, request);
            return result.getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Could not resolve definitions artifact " + gav, e);
        }
    }

    private Path resolveFromGit(String url, String reference) throws MojoExecutionException {
        if (reference.isBlank()) {
            throw new MojoExecutionException("A git 'repository' needs a 'ref' (tag or commit).");
        }
        var target = definitionsCache.toPath().resolve("git");
        try {
            cleanTree(target);
            Files.createDirectories(target);
            run(target.getParent(), "git", "clone", "--quiet", "--no-checkout", url, target.toString());
            if (isBranch(target, reference)) {
                if (!allowBranch) {
                    throw new MojoExecutionException("Refusing git branch ref '" + reference
                            + "' — pin a tag or commit, or set allowBranch=true.");
                }
                getLog().warn("Using git branch ref '" + reference + "' — not reproducible.");
            }
            run(target, "git", "checkout", "--quiet", reference);
            return target;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MojoExecutionException("Could not check out " + url + "@" + reference, e);
        }
    }

    private boolean isBranch(Path repo, String reference) throws IOException, InterruptedException {
        var process = new ProcessBuilder("git", "ls-remote", "--heads", "origin", reference)
                .directory(repo.toFile()).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return !out.isBlank();
    }

    private void run(Path workingDir, String... command) throws IOException, InterruptedException,
            MojoExecutionException {
        var process = new ProcessBuilder(command).directory(workingDir.toFile())
                .redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new MojoExecutionException("Command failed: " + String.join(" ", command) + "\n" + out);
        }
    }

    /** Prefer a {@code tasks/} or {@code definitions/tasks/} folder inside a bundle; else the root. */
    static Path findTasksDirectory(Path root) {
        for (var candidate : List.of(root.resolve("tasks"),
                root.resolve("definitions").resolve("tasks"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return root;
    }

    static void unzip(Path zip, Path target) throws IOException {
        cleanTree(target);
        Files.createDirectories(target);
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                var resolved = target.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(target)) {
                    throw new IOException("Zip entry escapes target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(in, resolved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Set<String> split(String csv) {
        var set = new LinkedHashSet<String>();
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
        }
        return set;
    }

    private void registerRoots() {
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        var resource = new Resource();
        resource.setDirectory(resourcesDirectory.getAbsolutePath());
        project.addResource(resource);
    }

    private static void cleanTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static List<Path> listDefinitionFiles(Path directory) throws MojoExecutionException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(GenerateMojo::isDefinitionFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not scan " + directory, e);
        }
    }

    private static boolean isDefinitionFile(Path path) {
        var name = path.getFileName().toString().toLowerCase();
        return DEFINITION_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static JsonNode parse(Path file) throws IOException {
        var name = file.getFileName().toString().toLowerCase();
        return (name.endsWith(".json") ? JSON : YAML).readTree(file.toFile());
    }
}
