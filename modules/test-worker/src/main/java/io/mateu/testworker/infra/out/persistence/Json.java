package io.mateu.testworker.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * The JSON columns, read and written.
 *
 * <p>Reading is lenient and writing is not. A column that will not parse yields an empty list and
 * a logged error, because the alternative is a page that will not open and an override nobody can
 * fix; a value that will not serialise is a bug in this worker and is left to surface.
 */
@Slf4j
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static <T> List<T> listFrom(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, type));
        } catch (Exception e) {
            log.error("A stored JSON column could not be read from '{}' — reading it as empty", json, e);
            return List.of();
        }
    }

    static <T> T from(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.error("A stored JSON column could not be read from '{}' — falling back", json, e);
            return fallback;
        }
    }

    /** The list as JSON, or null when it is empty — no rows full of "[]". */
    static String toJson(List<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("This worker wrote a value it cannot serialise", e);
        }
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            log.error("'{}' is not a {} any more — reading it as absent", name, type.getSimpleName());
            return null;
        }
    }

    static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
