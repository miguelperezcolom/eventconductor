package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.services.DefinitionFileFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workflow definition files on the classpath: every {@code workflows/} directory of every
 * classpath root (directories and jars), not only the first one found.
 *
 * <p>It used to be {@code classpath:/workflows/…}, which Spring resolves against a single root: in a
 * test run, where {@code target/test-classes} comes before {@code target/classes}, a
 * {@code src/test/resources/workflows} directory hid every definition in
 * {@code src/main/resources/workflows} — and a stale {@code target/test-classes/workflows} kept hiding
 * them until {@code mvn clean}.
 *
 * <p><b>The same id in more than one root: the first on the classpath wins.</b> That is the order the
 * JVM resolves a class in, and the order Maven, Gradle and the IDEs put test output before main output
 * — so a definition under {@code src/test/resources/workflows} overrides the one with the same id under
 * {@code src/main/resources/workflows}, and either beats one packaged in a dependency jar. A shadowed
 * copy whose content differs is logged as a warning naming both locations; an identical copy (the
 * same file on the classpath twice) is not. Within one root, files are read in name order.
 *
 * <p>Schedule ({@code cron.yaml}) and event-trigger ({@code events.yaml}) files sit beside the
 * definitions but are not definitions; they are left out here.
 */
@Slf4j
public final class ClasspathDefinitionFiles {

    /** The directory definitions are read from, in every classpath root. */
    public static final String DIRECTORY = "workflows/";

    /**
     * One definition file.
     *
     * @param filename its name, as a log line names it
     * @param location where it was found — a {@code file:} or {@code jar:} URL — so two copies can be told apart
     * @param bytes    its content
     */
    public record DefinitionFile(String filename, String location, byte[] bytes) {}

    private ClasspathDefinitionFiles() {}

    /** Every definition file under {@code workflows/} in every classpath root, first root first. */
    public static List<DefinitionFile> scan() throws IOException {
        return scan(new PathMatchingResourcePatternResolver());
    }

    /** {@link #scan()} with the given resolver (a test points it at a class loader of its own). */
    public static List<DefinitionFile> scan(ResourcePatternResolver resolver) throws IOException {
        // The roots in class-loader order: this is what "first on the classpath" means.
        var rootOrder = new HashMap<String, Integer>();
        var roots = resolver.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + DIRECTORY);
        for (int i = 0; i < roots.length; i++) {
            rootOrder.putIfAbsent(rootKey(roots[i], true), i);
        }
        var resources = new ArrayList<Resource>();
        // .json, .yaml/.yml (which covers order.workflow.yaml and order.ec.yaml) and .ec — the
        // patterns don't overlap, so nothing is read twice.
        for (var pattern : DefinitionFileFormat.CLASSPATH_PATTERNS) {
            resources.addAll(Arrays.asList(
                    resolver.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + DIRECTORY + pattern)));
        }
        resources.removeIf(r -> io.mateu.workflow.analysis.ScheduleRules.isCronFileName(r.getFilename()));
        resources.removeIf(r -> io.mateu.workflow.analysis.EventTriggerRules.isEventFileName(r.getFilename()));
        // Each pattern lists its matches root by root; regroup them so a root's files come together
        // and the roots come in class-loader order (a root the listing did not name goes last).
        resources.sort(Comparator
                .comparingInt((Resource r) -> rootOrder.getOrDefault(rootKey(r, false), Integer.MAX_VALUE))
                .thenComparing(ClasspathDefinitionFiles::location));
        var files = new ArrayList<DefinitionFile>();
        for (var resource : resources) {
            try {
                files.add(new DefinitionFile(resource.getFilename(), location(resource),
                        resource.getInputStream().readAllBytes()));
            } catch (IOException e) {
                log.error("Failed to read workflow definition {}", location(resource), e);
            }
        }
        return files;
    }

    /** Where a resource is, for logs and for telling two copies apart. */
    static String location(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }

    /**
     * The {@code workflows/} directory a resource is in (or, for a root, is): its canonical path for a
     * directory on disk, so a URL spelled two ways (percent-encoded or not) is still one root; the URL
     * up to the last {@code /} otherwise.
     */
    private static String rootKey(Resource resource, boolean isRoot) {
        try {
            if (resource.isFile()) {
                var file = resource.getFile();
                return (isRoot ? file : file.getParentFile()).getCanonicalPath();
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the URL
        }
        var url = location(resource);
        if (isRoot) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(0, slash);
    }

    /**
     * Applies the first-wins rule as definitions are read: {@link #claim} says whether a definition is
     * the one that counts for its id.
     */
    public static final class FirstWins {

        private final Map<String, DefinitionFile> claimed = new LinkedHashMap<>();

        /**
         * True when {@code file} is the first definition read with this {@code id}; false when an earlier
         * root already provided it — logged as a warning when the content differs.
         */
        public boolean claim(String id, DefinitionFile file) {
            if (id == null) {
                return true;   // nothing to collide on; the loader refuses it as it always has
            }
            var winner = claimed.putIfAbsent(id, file);
            if (winner == null) {
                return true;
            }
            if (Arrays.equals(winner.bytes(), file.bytes())) {
                log.debug("Workflow definition '{}' at {} is identical to the one at {}; ignored",
                        id, file.location(), winner.location());
            } else {
                log.warn("Workflow definition '{}' is on the classpath more than once with different content;"
                                + " using {} (first on the classpath — test resources come before main ones)"
                                + " and ignoring {}",
                        id, winner.location(), file.location());
            }
            return false;
        }
    }
}
