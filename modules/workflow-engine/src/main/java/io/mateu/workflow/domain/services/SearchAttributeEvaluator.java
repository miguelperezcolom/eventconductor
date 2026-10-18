package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.analysis.SearchAttributeRules;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.search.SearchAttributeType;
import io.mateu.workflow.search.SearchAttributeValue;
import io.mateu.workflow.template.Templates;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A process's search attributes, computed: the {@code searchAttributes} of the definition version it
 * runs — read from its definition snapshot — evaluated against its variables, in the same template
 * sandbox and context as a payload template ({@link TemplateContext}: the variables, {@code process},
 * {@code businessKey}).
 *
 * <p>An attribute whose expression fails, or whose value is empty or not of its type, is not indexed:
 * a search attribute is a way to find a process, never a reason for one to fail.
 *
 * <p>The declarations of a definition version are parsed once and cached: a process is saved many
 * times, and its snapshot is several KB of JSON. A snapshot that never mentions search attributes is
 * recognised without parsing at all.
 */
@Slf4j
public final class SearchAttributeEvaluator {

    /** One declared attribute, ready to evaluate. */
    public record Spec(String name, String value, SearchAttributeType type) {
    }

    private static final String KEY = "\"searchAttributes\"";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CACHE_SIZE = 512;

    private record CacheKey(String definitionId, int version, int length, int hash) {
    }

    private static final Map<CacheKey, List<Spec>> CACHE = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, List<Spec>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private SearchAttributeEvaluator() {
    }

    /** Whether the process's definition version declares any search attribute. */
    public static boolean declares(Process process) {
        return !specsOf(process).isEmpty();
    }

    /** The declarations of the process's definition version; empty when it declares none. */
    public static List<Spec> specsOf(Process process) {
        var json = process == null ? null : process.getWorkflowDefinitionJson();
        if (json == null || !json.contains(KEY)) {
            return List.of();
        }
        var key = new CacheKey(process.getWorkflowDefinitionId(), process.getWorkflowDefinitionVersion(),
                json.length(), json.hashCode());
        return CACHE.computeIfAbsent(key, ignored -> parse(json));
    }

    @SuppressWarnings("unchecked")
    private static List<Spec> parse(String definitionJson) {
        try {
            var node = JSON.readTree(definitionJson).get("searchAttributes");
            if (node == null || !node.isObject()) {
                return List.of();
            }
            Map<String, Object> declared = JSON.convertValue(node, LinkedHashMap.class);
            if (!SearchAttributeRules.violations(declared, null).isEmpty()) {
                // Refused at load; a snapshot that somehow carries a bad declaration indexes nothing.
                return List.of();
            }
            var specs = new ArrayList<Spec>();
            for (var declaration : SearchAttributeRules.declarations(declared)) {
                specs.add(new Spec(declaration.name(), declaration.value(), SearchAttributeType.of(declaration.type())));
            }
            return List.copyOf(specs);
        } catch (Exception e) {
            log.debug("Could not read the search attributes of a definition snapshot: {}", e.getMessage());
            return List.of();
        }
    }

    /** The process's search attributes as they are now; empty when its definition declares none. */
    public static List<SearchAttributeValue> evaluate(Process process) {
        var specs = specsOf(process);
        if (specs.isEmpty()) {
            return List.of();
        }
        var context = TemplateContext.of(process, null, null);
        var values = new ArrayList<SearchAttributeValue>(specs.size());
        for (var spec : specs) {
            try {
                SearchAttributeValue.of(spec.name(), spec.type(), Templates.render(spec.value(), context))
                        .ifPresent(values::add);
            } catch (RuntimeException e) {
                log.debug("Search attribute '{}' of process {} not indexed: {}", spec.name(), process.getId(),
                        e.getMessage());
            }
        }
        return List.copyOf(values);
    }
}
