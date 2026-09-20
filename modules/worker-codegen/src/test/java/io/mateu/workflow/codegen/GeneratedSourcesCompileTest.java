package io.mateu.workflow.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the generated sources with the real {@code javac}, against minimal stubs of the
 * worker-api, Jackson and Spring types they reference. It is the proof that the generator emits
 * legal Java — records, an interface, nested exception subclasses, an autoconfiguration — not just
 * text that happens to contain the right substrings.
 */
class GeneratedSourcesCompileTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stubs for everything the generated code refers to outside its own package. */
    private static final Map<String, String> STUBS = Map.of(
            "io.mateu.workflow.worker.api.TaskHandler",
            "package io.mateu.workflow.worker.api; public interface TaskHandler<I, O> {}",
            "io.mateu.workflow.worker.api.TaskFailure",
            "package io.mateu.workflow.worker.api; public class TaskFailure extends RuntimeException {"
                    + " public TaskFailure(String c) { super(c); }"
                    + " public TaskFailure(String c, String r) { super(r); } }",
            "io.mateu.workflow.worker.api.TaskRegistration",
            "package io.mateu.workflow.worker.api; public record TaskRegistration<I, O>("
                    + "String id, int version, String topic, Class<I> inputType, Class<O> outputType,"
                    + " Object handler) {}",
            "com.fasterxml.jackson.databind.JsonNode",
            "package com.fasterxml.jackson.databind; public class JsonNode {}",
            "com.fasterxml.jackson.annotation.JsonProperty",
            "package com.fasterxml.jackson.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME)"
                    + " @Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.FIELD})"
                    + " public @interface JsonProperty { String value() default \"\"; }",
            "org.springframework.boot.autoconfigure.AutoConfiguration",
            "package org.springframework.boot.autoconfigure; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)"
                    + " public @interface AutoConfiguration {}",
            "org.springframework.context.annotation.Bean",
            "package org.springframework.context.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)"
                    + " public @interface Bean {}",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
            "package org.springframework.boot.autoconfigure.condition; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})"
                    + " public @interface ConditionalOnProperty { String name() default \"\";"
                    + " boolean matchIfMissing() default false; }");

    private static JsonNode contract(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String fqcn, String code) {
            super(URI.create("string:///" + fqcn.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    @Test
    void the_generated_sources_compile(@TempDir Path out) throws IOException {
        var generated = new TaskSourceGenerator("com.acme.tasks").generate(List.of(
                contract("""
                    {"id": "confirm-booking", "version": 1, "group": "booking", "topic": "booking-ops",
                     "input": {"bookingId": {"type": "string"}, "nights": {"type": "integer"},
                               "guests": {"type": "array", "items": {"type": "string"}},
                               "meta": {"type": "object"}, "order-id": {"type": "string"}},
                     "output": {"confirmationCode": {"type": "string"}},
                     "errors": [{"code": "SOLD_OUT"}, {"code": "ALREADY_CONFIRMED"}]}"""),
                contract("""
                    {"id": "confirm-booking", "version": 2, "group": "booking",
                     "output": {"code": {"type": "string"}}}"""),
                contract("""
                    {"id": "ping", "version": 1, "group": "ops"}""")));

        var units = new ArrayList<JavaFileObject>();
        STUBS.forEach((fqcn, code) -> units.add(new StringSource(fqcn, code)));
        for (var file : generated.javaFiles()) {
            units.add(new StringSource(file.packageName() + "." + file.typeName(), file.source()));
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var classes = Files.createDirectory(out.resolve("classes"));
            var ok = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", classes.toString()), null, units).call();

            var errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null))
                    .toList();
            assertThat(errors).isEmpty();
            assertThat(ok).isTrue();
        }
    }
}
