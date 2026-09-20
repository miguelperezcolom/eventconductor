package io.mateu.workflow.codegen;

import java.util.List;

/**
 * A whole generated project skeleton: the project files (pom, README, and for a standalone service
 * the wrapper, {@code .gitignore} and {@code application.yaml}) as path/content pairs. It carries
 * only project files — never Java, which the Maven goal generates into {@code generated-sources} at
 * build time (decision 12). The UI delivers this as a zip; the IDE writes the files in place.
 */
public record GeneratedProject(List<GeneratedProjectFile> files) {

    /** One project file: a repository-relative path and its text content. */
    public record GeneratedProjectFile(String path, String content) {
    }
}
