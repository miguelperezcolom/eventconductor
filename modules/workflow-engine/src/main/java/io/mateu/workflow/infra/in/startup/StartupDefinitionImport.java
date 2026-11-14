package io.mateu.workflow.infra.in.startup;

/**
 * An import of local definitions (classpath, configured directories) that must be finished before the
 * engine serves a request. Run by {@link StartupDefinitionImports} before the web server starts, in
 * {@link org.springframework.core.annotation.Order @Order} — not as an
 * {@link org.springframework.boot.ApplicationRunner}, which Spring Boot calls only after the HTTP
 * connector is already accepting requests (a start in that window would be told the definition does
 * not exist).
 *
 * <p>For imports that read local files — plus the git import of task contracts, which every workflow
 * import resolves task references against. The git import of workflow definitions stays an
 * {@code ApplicationRunner} so a slow or unreachable repository never holds the server back; until it
 * (and every other runner) has finished, the readiness probe is down and a start of a definition not
 * loaded yet is answered 503 + {@code Retry-After} instead of 404.
 */
public interface StartupDefinitionImport {

    void importDefinitions();
}
