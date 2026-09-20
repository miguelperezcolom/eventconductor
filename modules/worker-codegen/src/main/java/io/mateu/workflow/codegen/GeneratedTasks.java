package io.mateu.workflow.codegen;

import java.util.List;

/**
 * The whole output of a source-generation run: the Java sources to write under the generated-sources
 * root, and the {@code AutoConfiguration.imports} listing the generated per-group configurations so
 * the registrations are found without the application having to component-scan them. Empty imports
 * mean nothing was generated.
 */
public record GeneratedTasks(List<GeneratedJavaFile> javaFiles, String autoConfigurationImports) {
}
