package io.mateu.workflow.domain.services;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON Schema (2020-12) checks: whether a schema is itself valid (checked at import, e.g. an update's
 * {@code accepts}) and whether a document satisfies one (e.g. an update's arguments). Compiled schemas
 * are cached by their text.
 */
public final class JsonSchemas {

    private static final int MAX_REPORTED = 5;
    private static final String META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> CACHE = new ConcurrentHashMap<>();
    private static volatile Schema metaSchema;

    /** What is wrong with {@code document} against {@code schema}; empty when valid. */
    public static List<String> validate(Map<String, Object> schema, String document) {
        var json = MultiInstance.toJson(schema);
        try {
            var compiled = CACHE.computeIfAbsent(json, text -> {
                var s = REGISTRY.getSchema(text, InputFormat.JSON);
                s.initializeValidators();
                return s;
            });
            return compiled.validate(document, InputFormat.JSON).stream().limit(MAX_REPORTED)
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList();
        } catch (RuntimeException e) {
            return List.of("the document could not be validated: " + e.getMessage());
        }
    }

    /** Why {@code schema} is not a valid JSON Schema; empty when it is. */
    public static List<String> problems(Map<String, Object> schema) {
        try {
            return meta().validate(MultiInstance.toJson(schema), InputFormat.JSON).stream().limit(MAX_REPORTED)
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList();
        } catch (RuntimeException e) {
            return List.of(String.valueOf(e.getMessage()));
        }
    }

    private static Schema meta() {
        var meta = metaSchema;
        if (meta == null) {
            meta = REGISTRY.getSchema(SchemaLocation.of(META_SCHEMA));
            metaSchema = meta;
        }
        return meta;
    }

    private JsonSchemas() {
    }
}
