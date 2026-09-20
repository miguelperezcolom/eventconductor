package io.mateu.workflow.codegen;

/**
 * One generated Java source: its package, its top-level type name, and the source text. The
 * relative path a caller writes it to is derived from the two, so the caller never assembles paths.
 */
public record GeneratedJavaFile(String packageName, String typeName, String source) {

    /** {@code com/acme/Foo.java} — the path under the generated-sources root. */
    public String relativePath() {
        return packageName.replace('.', '/') + "/" + typeName + ".java";
    }
}
