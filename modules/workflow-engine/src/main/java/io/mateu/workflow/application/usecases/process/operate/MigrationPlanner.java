package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.analysis.MigrationRules;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.StepExecutions;
import io.mateu.workflow.domain.services.StepTimeoutDefaults;
import io.mateu.workflow.application.out.WorkflowDefinitionHistory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Plans the move of a live process onto another version of its definition: resolves the target version,
 * reads the process's facts off its current executions and asks the shared rules
 * ({@link MigrationRules#plan}) what happens to every step and whether it is refused. Asked by the door
 * (the REST API's dry run and its {@code 409}, the UI's preview, MCP) and by {@link MigrateProcessUseCase}
 * under the process lock — the same plan both times, from the state each one sees.
 */
@Component
public class MigrationPlanner {

    private final WorkflowDefinitionRepository definitions;
    private final ObjectProvider<WorkflowDefinitionHistory> versions;
    private final ProcessOperationRules rules;
    private final long defaultStepTimeoutMillis;

    public MigrationPlanner(WorkflowDefinitionRepository definitions,
                            ObjectProvider<WorkflowDefinitionHistory> versions,
                            ProcessOperationRules rules,
                            @Value("${workflow.default-step-timeout-ms:0}") long defaultStepTimeoutMillis) {
        this.definitions = definitions;
        this.versions = versions;
        this.rules = rules;
        this.defaultStepTimeoutMillis = defaultStepTimeoutMillis;
    }

    /** Whether stored values can be encrypted: a target that declares sensitive variables needs it. */
    private io.mateu.workflow.crypto.VariableEncryption encryption = io.mateu.workflow.crypto.VariableEncryption.DISABLED;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void encryption(io.mateu.workflow.crypto.VariableEncryption encryption) {
        this.encryption = encryption == null ? io.mateu.workflow.crypto.VariableEncryption.DISABLED : encryption;
    }

    /** A plan, with the two versions it was made between. */
    public record Planned(WorkflowDefinition source, WorkflowDefinition target, MigrationRules.Plan plan) {

        /** The target's step of this id, or null. */
        public Step targetStep(String id) {
            return target.steps().stream().filter(s -> Objects.equals(id, s.id())).findFirst().orElse(null);
        }

        /** The 1-based position of a target step, as a process created from the target would have it. */
        public int position(String id) {
            var steps = target.steps();
            for (int i = 0; i < steps.size(); i++) {
                if (Objects.equals(id, steps.get(i).id())) {
                    return i + 1;
                }
            }
            return steps.size() + 1;
        }

        public int fromVersion() {
            return source == null ? 0 : source.version();
        }
    }

    /**
     * The plan for {@code process} as its {@code executions} stand.
     *
     * @param targetVersion   null for the definition as it is now
     * @throws UnknownWorkflowDefinitionException its definition is gone
     * @throws IllegalArgumentException            no such version
     */
    public Planned plan(Process process, List<StepExecution> executions, Integer targetVersion,
                        Map<String, String> stepMapping, Map<String, String> variableMapping) {
        var target = target(process.getWorkflowDefinitionId(), targetVersion);
        var source = snapshot(process);
        var current = StepExecutions.current(executions).stream()
                .sorted(Comparator.comparingLong(StepExecution::getOrder)).toList();

        var sourceSteps = new ArrayList<Map<?, ?>>();
        var seen = new java.util.HashSet<String>();
        for (var execution : current) {
            if (execution.getInjectedByStepExecutionId() == null && seen.add(execution.getStepId())) {
                sourceSteps.add(generic(execution.getStepJson()));
            }
        }
        if (source != null) {
            source.steps().stream().filter(step -> seen.add(step.id())).map(step -> generic(toJson(step)))
                    .forEach(sourceSteps::add);
        }
        var targetSteps = target.steps().stream().map(step -> generic(toJson(step))).toList();
        var states = current.stream().map(execution -> state(execution, current)).toList();
        var facts = new MigrationRules.ProcessFacts(
                process.getStatus() == null ? null : process.getStatus().name(),
                rules.rollbackUnderway(process, executions),
                source != null && source.processLock() != null
                        && (ProcessStatus.RUNNING.equals(process.getStatus()) || ProcessStatus.PAUSED.equals(process.getStatus())),
                process.getVariables().stream().map(io.mateu.workflow.domain.aggregates.Variable::name)
                        .collect(Collectors.toSet()));
        var plan = MigrationRules.plan(new MigrationRules.Input(sourceSteps,
                source == null || source.processLock() == null ? null : generic(toJson(source.processLock())),
                targetSteps, target.processLock() == null ? null : generic(toJson(target.processLock())),
                stepMapping, variableMapping, states, facts));
        return new Planned(source, target, withSensitivity(plan, source, target, process, variableMapping));
    }

    /**
     * The sensitivity changes between the two versions, for this process's variables (warnings: the
     * migration re-encodes the stored values as the target declares), and a refusal when the target declares
     * sensitive variables and this engine has no key to encrypt them with. Names only, never values.
     */
    private MigrationRules.Plan withSensitivity(MigrationRules.Plan plan, WorkflowDefinition source,
                                                WorkflowDefinition target, Process process,
                                                Map<String, String> variableMapping) {
        var sourcePatterns = source == null ? List.<String>of() : source.sensitiveVariablePatterns();
        var targetPatterns = target.sensitiveVariablePatterns();
        if (sourcePatterns.isEmpty() && targetPatterns.isEmpty()) {
            return plan;
        }
        var names = new java.util.LinkedHashSet<String>();
        process.getVariables().forEach(variable -> names.add(variable.name()));
        if (variableMapping != null) {
            names.addAll(variableMapping.keySet());
        }
        var problems = new ArrayList<>(plan.problems());
        problems.addAll(MigrationRules.sensitivityProblems(sourcePatterns, targetPatterns, names));
        if (!targetPatterns.isEmpty() && !encryption.enabled()) {
            problems.add(new MigrationRules.Problem(MigrationRules.Severity.ERROR, null, "the target declares sensitive"
                    + " variables " + targetPatterns + " but no encryption key is configured (workflow.encryption.keys.<id>)"));
        }
        return new MigrationRules.Plan(plan.mapping(), plan.steps(), List.copyOf(problems));
    }

    /**
     * The version to move to: the definition as it is now (null, or its own number), else a recorded
     * version from the history — prepared as a process created from it would freeze it (the steps
     * stamped with their definition, the fallback step timeout applied).
     */
    public WorkflowDefinition target(String definitionId, Integer version) {
        var current = definitions.findById(definitionId)
                .orElseThrow(() -> new UnknownWorkflowDefinitionException(definitionId));
        if (version == null || version == current.version()) {
            return prepared(current);
        }
        var history = versions.getIfAvailable();
        if (history == null) {
            throw new IllegalArgumentException("Version " + version + " of workflow '" + definitionId
                    + "' is not available: this engine keeps no version history (workflow.persistence=memory);"
                    + " only the current version, v" + current.version() + ", can be migrated to.");
        }
        return prepared(history.find(definitionId, version)
                .orElseThrow(() -> new IllegalArgumentException("Workflow '" + definitionId + "' has no version "
                        + version + ".")));
    }

    /** The versions a process of {@code definitionId} can be moved to, newest first. */
    public List<Integer> versions(String definitionId) {
        var history = versions.getIfAvailable();
        var numbers = new java.util.TreeSet<Integer>(Comparator.reverseOrder());
        if (history != null) {
            numbers.addAll(history.versions(definitionId));
        }
        definitions.findById(definitionId).ifPresent(d -> numbers.add(d.version()));
        return List.copyOf(numbers);
    }

    private WorkflowDefinition prepared(WorkflowDefinition definition) {
        var id = definition.id();
        var steps = definition.steps() == null ? List.<Step>of()
                : definition.steps().stream().map(step -> step.withWorkflowDefinitionId(id)).toList();
        return StepTimeoutDefaults.applyTo(definition.withSteps(steps), defaultStepTimeoutMillis);
    }

    /** The definition the process runs, from its snapshot; null when it has none (old fixtures). */
    static WorkflowDefinition snapshot(Process process) {
        var json = process.getWorkflowDefinitionJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return pojoFromJson(json, WorkflowDefinition.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> generic(String json) {
        try {
            return json == null ? Map.of() : (Map<String, Object>) pojoFromJson(json, LinkedHashMap.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** What the rules need to know of one current execution. */
    static MigrationRules.StepState state(StepExecution execution, List<StepExecution> current) {
        var step = ProcessOperationRules.stepOf(execution);
        var status = execution.getStatus();
        MigrationRules.State state = switch (status == null ? StepExecutionStatus.CREATED : status) {
            case CREATED -> MigrationRules.State.NOT_STARTED;
            case COMPLETED -> MigrationRules.State.COMPLETED;
            case CANCELLED -> MigrationRules.State.SKIPPED;
            case ERROR -> MigrationRules.State.FAILED;
            case TIMEOUT -> ProcessOperationRules.blocksTheFlow(execution) ? MigrationRules.State.FAILED
                    : MigrationRules.State.SKIPPED;
            case PENDING, RUNNING, AWAITING_RETRY, WAITING_ON_LOCK -> MigrationRules.State.ACTIVE;
        };
        var type = step == null || step.type() == null ? null : step.type().name();
        var atWorker = status != null && status.isInFlightAtAWorker() && !execution.isContainer()
                && type != null && MigrationRules.DISPATCHING_TYPES.contains(type)
                && execution.getAwaitingMessageName() == null;
        return new MigrationRules.StepState(execution.getStepId(), state, atWorker,
                StepExecutionStatus.WAITING_ON_LOCK.equals(status), holdsLock(execution, step, current),
                execution.getInjectedByStepExecutionId() != null,
                step != null && StepType.USER_TASK.equals(step.type()) ? execution.escalationsFired() : 0);
    }

    /** A completed LOCK whose section no completed UNLOCK of the same lock has closed. */
    private static boolean holdsLock(StepExecution execution, Step step, List<StepExecution> current) {
        if (step == null || !StepType.LOCK.equals(step.type())
                || !StepExecutionStatus.COMPLETED.equals(execution.getStatus())) {
            return false;
        }
        return current.stream()
                .filter(se -> StepExecutionStatus.COMPLETED.equals(se.getStatus()))
                .map(ProcessOperationRules::stepOf)
                .filter(Objects::nonNull)
                .noneMatch(other -> StepType.UNLOCK.equals(other.type())
                        && Objects.equals(other.lockName(), step.lockName())
                        && Objects.equals(other.lockKey(), step.lockKey()));
    }
}
