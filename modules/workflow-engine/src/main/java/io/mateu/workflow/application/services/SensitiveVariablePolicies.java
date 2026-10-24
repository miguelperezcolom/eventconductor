package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.crypto.SensitiveNames;
import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.HttpRequestRenderer;
import io.mateu.workflow.domain.services.LlmRequestRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Which variables are sensitive, for whatever the engine is about to write or show — a process, a step
 * execution, an event on its way to the outbox or the broker.
 *
 * <ul>
 *   <li><b>A process</b>: what the definition version it runs declares, read from its definition snapshot
 *       (parsed once per version and cached, like the search attributes). Every process read or saved
 *       registers its set under its id.</li>
 *   <li><b>A process id</b> (a step execution, an event): the registered set, else the process is loaded
 *       once to find it.</li>
 *   <li><b>A definition id</b> (a creation request, before any process exists): what its current
 *       version declares.</li>
 *   <li><b>Nothing to go by</b> (a message, which correlates to processes not known yet): the union of
 *       every definition's — protecting too much is the safe way to be wrong.</li>
 * </ul>
 *
 * <p>Whenever a set is not empty it also covers the engine's own derived carriers — the rendered HTTP
 * request ({@code __http}), LLM request ({@code __llm}), agent request ({@code __agent}) and published event
 * ({@code __event}) — since they embed
 * rendered values.
 */
@Component
@Slf4j
public class SensitiveVariablePolicies {

    /** Variables the engine derives from others, sensitive whenever anything is. */
    public static final List<String> DERIVED_CARRIERS = List.of(
            HttpRequestRenderer.REQUEST_VARIABLE, LlmRequestRenderer.REQUEST_VARIABLE,
            io.mateu.workflow.domain.services.AgentRequestRenderer.REQUEST_VARIABLE,
            io.mateu.workflow.domain.services.ExternalEventRenderer.REQUEST_VARIABLE);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SNAPSHOT_CACHE_SIZE = 512;
    private static final int PROCESS_CACHE_SIZE = 20_000;
    private static final long DEFINITION_TTL_NANOS = 5_000_000_000L;

    private record SnapshotKey(String definitionId, int version, int length, int hash) {
    }

    private record Timed(SensitiveNames names, long at) {
    }

    private static final Map<SnapshotKey, SensitiveNames> SNAPSHOTS = lru(SNAPSHOT_CACHE_SIZE);

    private final Map<String, SensitiveNames> byProcess = lru(PROCESS_CACHE_SIZE);
    private final Map<String, Timed> byDefinition = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile Timed global;

    private final VariableEncryption encryption;
    private final ObjectProvider<ProcessRepository> processes;
    private final ObjectProvider<WorkflowDefinitionRepository> definitions;

    public SensitiveVariablePolicies(ObjectProvider<VariableEncryption> encryption,
                                     ObjectProvider<ProcessRepository> processes,
                                     ObjectProvider<WorkflowDefinitionRepository> definitions) {
        this.encryption = encryption.getIfAvailable(() -> VariableEncryption.DISABLED);
        this.processes = processes;
        this.definitions = definitions;
    }

    /** A policy that resolves nothing — for code built outside Spring (tests, tools). */
    public static SensitiveVariablePolicies none() {
        return new SensitiveVariablePolicies(new FixedProvider<>(VariableEncryption.DISABLED),
                new FixedProvider<>(null), new FixedProvider<>(null));
    }

    /** The encryption this engine runs with. */
    public VariableEncryption encryption() {
        return encryption;
    }

    /** Whether values are sealed at all: a key is configured. */
    public boolean enabled() {
        return encryption.enabled();
    }

    /** The sensitive variables of a process, from its definition snapshot; registered under its id. */
    public SensitiveNames of(Process process) {
        if (process == null) {
            return SensitiveNames.NONE;
        }
        var names = ofSnapshot(process.getWorkflowDefinitionId(), process.getWorkflowDefinitionVersion(),
                process.getWorkflowDefinitionJson());
        if (process.getId() != null) {
            byProcess.put(process.getId(), names);
        }
        return names;
    }

    /** The sensitive variables of the process with this id: registered, else looked up once; none when unknown. */
    public SensitiveNames ofProcessId(String processId) {
        var names = resolveProcess(processId);
        return names == null ? SensitiveNames.NONE : names;
    }

    /** The registered or looked-up set of a process; null when there is no such process (yet). */
    private SensitiveNames resolveProcess(String processId) {
        if (processId == null || processId.isBlank()) {
            return null;
        }
        var known = byProcess.get(processId);
        if (known != null) {
            return known;
        }
        var repository = processes.getIfAvailable();
        if (repository == null) {
            return null;
        }
        try {
            return repository.findById(processId).map(this::of).orElse(null);
        } catch (RuntimeException e) {
            log.debug("Could not resolve the sensitive variables of process {}: {}", processId, e.toString());
            return null;
        }
    }

    /** What the current version of this definition declares. */
    public SensitiveNames ofDefinitionId(String definitionId) {
        if (definitionId == null || definitionId.isBlank()) {
            return global();
        }
        var cached = byDefinition.get(definitionId);
        if (cached != null && System.nanoTime() - cached.at() < DEFINITION_TTL_NANOS) {
            return cached.names();
        }
        var repository = definitions.getIfAvailable();
        SensitiveNames names;
        try {
            names = repository == null ? SensitiveNames.NONE : repository.findById(definitionId)
                    .map(SensitiveVariablePolicies::of).orElse(SensitiveNames.NONE);
        } catch (RuntimeException e) {
            log.debug("Could not resolve the sensitive variables of definition {}: {}", definitionId, e.toString());
            names = SensitiveNames.NONE;
        }
        byDefinition.put(definitionId, new Timed(names, System.nanoTime()));
        return names;
    }

    /** The union of what every definition declares. */
    public SensitiveNames global() {
        var cached = global;
        if (cached != null && System.nanoTime() - cached.at() < DEFINITION_TTL_NANOS) {
            return cached.names();
        }
        var all = new LinkedHashSet<String>();
        var repository = definitions.getIfAvailable();
        if (repository != null) {
            try {
                for (var definition : repository.findAll()) {
                    all.addAll(definition.sensitiveVariablePatterns());
                }
            } catch (RuntimeException e) {
                log.debug("Could not list the definitions' sensitive variables: {}", e.toString());
            }
        }
        var names = withCarriers(SensitiveNames.of(all));
        global = new Timed(names, System.nanoTime());
        return names;
    }

    /**
     * The sensitive variables for an event, a message or any payload, from the ids it carries: its
     * {@code processId}, else its {@code workflowDefinitionId}, else the union of every definition's.
     */
    public SensitiveNames ofPayload(JsonNode payload) {
        if (payload != null && payload.has("variableMapping")) {
            // A migration request sets variables for the version it moves to, which may declare more than the
            // one the process runs: protected by every definition's union, as a message is.
            return global();
        }
        var process = resolveProcess(text(payload, "processId"));
        if (process != null) {
            return process;
        }
        var definitionId = text(payload, "workflowDefinitionId");
        return definitionId != null ? ofDefinitionId(definitionId) : global();
    }

    /** Forgets the cached definition-level sets (a definition was imported or changed). */
    public void definitionsChanged() {
        byDefinition.clear();
        global = null;
    }

    /** What a definition declares (patterns + derived carriers when any). */
    public static SensitiveNames of(WorkflowDefinition definition) {
        return definition == null ? SensitiveNames.NONE
                : withCarriers(SensitiveNames.of(definition.sensitiveVariablePatterns()));
    }

    /** What a definition snapshot declares — cached per version; a snapshot that never mentions them is not parsed. */
    public static SensitiveNames ofSnapshot(String definitionId, int version, String definitionJson) {
        if (definitionJson == null
                || (!definitionJson.contains("\"sensitiveVariables\"") && !definitionJson.contains("\"sensitiveOutputs\""))) {
            return SensitiveNames.NONE;
        }
        var key = new SnapshotKey(definitionId, version, definitionJson.length(), definitionJson.hashCode());
        return SNAPSHOTS.computeIfAbsent(key, ignored -> parse(definitionJson));
    }

    private static SensitiveNames parse(String definitionJson) {
        try {
            var root = JSON.readTree(definitionJson);
            var patterns = new ArrayList<String>();
            var declared = root.get("sensitiveVariables");
            if (declared != null && declared.isArray()) {
                declared.forEach(node -> {
                    if (node.isTextual()) patterns.add(node.asText());
                });
            }
            var stepOutputs = new ArrayList<List<String>>();
            var steps = root.get("steps");
            if (steps != null && steps.isArray()) {
                for (var step : steps) {
                    var flag = step.get("sensitiveOutputs");
                    var outputs = step.get("outputVariables");
                    if (flag != null && flag.asBoolean(false) && outputs != null && outputs.isArray()) {
                        var names = new ArrayList<String>();
                        outputs.forEach(node -> {
                            if (node.isTextual()) names.add(node.asText());
                        });
                        stepOutputs.add(names);
                    }
                }
            }
            return withCarriers(SensitiveNames.of(
                    io.mateu.workflow.analysis.SensitiveVariableRules.effectivePatterns(patterns, stepOutputs)));
        } catch (Exception e) {
            // A snapshot that cannot be read protects everything it can: refusing to guess would leak.
            log.warn("Could not read the sensitive variables of a definition snapshot ({}); sealing every variable",
                    e.getClass().getSimpleName());
            return SensitiveNames.of(List.of("*"));
        }
    }

    private static SensitiveNames withCarriers(SensitiveNames names) {
        return names.isEmpty() ? names : names.plus(DERIVED_CARRIERS);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static <K, V> Map<K, V> lru(int size) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > size;
            }
        });
    }

    /** A provider of one fixed value, for {@link #none()}. */
    private record FixedProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }
    }
}
