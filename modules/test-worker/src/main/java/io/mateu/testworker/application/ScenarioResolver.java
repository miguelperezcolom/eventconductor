package io.mateu.testworker.application;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.testworker.domain.ResolvedScenario;
import io.mateu.testworker.domain.ScenarioConfig;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.testworker.domain.TaskOverride;
import io.mateu.testworker.domain.TaskScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Decides what a task is going to do, from the three places that can say so.
 *
 * <p>In order: the process's own {@code TEST_CONFIG} variable, then a matching override saved in
 * the UI, then the built-in default. <b>{@code TEST_CONFIG} wins</b>, and that ordering is the
 * important design decision here. A test states its scenario in the process it starts, so it must
 * get that scenario whatever else is in the database; an override that could quietly outrank it
 * would mean a suite whose result depends on a table someone edited by hand last Tuesday. The
 * overrides are for the other half of the job — driving a scenario by hand against processes that
 * carry no {@code TEST_CONFIG} at all.
 */
@Service
@Slf4j
public class ScenarioResolver {

    /**
     * The process variable a scenario travels in.
     *
     * <p>Matched without regard to case. Everything else about this worker is strict — an unknown
     * JSON property is an error rather than a shrug — precisely so that a scenario cannot silently
     * fail to apply, and a variable named {@code test_config} being silently ignored is the same
     * failure by a different route.
     */
    public static final String TEST_CONFIG = "TEST_CONFIG";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

    private final TaskOverrideStore overrides;
    private final Duration defaultDuration;

    public ScenarioResolver(TaskOverrideStore overrides,
                            @Value("${worker.task-duration:2s}") Duration defaultDuration) {
        this.overrides = overrides;
        this.defaultDuration = defaultDuration;
    }

    public ResolvedScenario resolve(TaskExecutionRequested task) {
        var baseline = TaskScenario.baseline(defaultDuration);
        var testConfig = testConfigIn(task.variables());
        if (testConfig != null) {
            return fromTestConfig(task, testConfig, baseline);
        }
        var override = matchingOverride(task);
        if (override != null) {
            return new ResolvedScenario(
                    override.toScenario().withFallback(baseline),
                    ScenarioSource.OVERRIDE,
                    override.toString());
        }
        return ResolvedScenario.of(baseline, ScenarioSource.DEFAULT);
    }

    private ResolvedScenario fromTestConfig(TaskExecutionRequested task, String json,
                                            TaskScenario baseline) {
        ScenarioConfig config;
        try {
            config = mapper.readValue(json, ScenarioConfig.class);
        } catch (Exception e) {
            throw new ScenarioNotReadableException(
                    "%s could not be read: %s".formatted(TEST_CONFIG, e.getMessage()), e);
        }
        return new ResolvedScenario(
                config.scenarioFor(task.taskId(), task.stepId(), baseline),
                ScenarioSource.TEST_CONFIG,
                matchedKey(config, task));
    }

    /** Which key in the config answered, for the record. Null when the default block did. */
    private String matchedKey(ScenarioConfig config, TaskExecutionRequested task) {
        if (config.tasks() == null) {
            return null;
        }
        if (config.tasks().containsKey(task.taskId())) {
            return task.taskId();
        }
        return config.tasks().containsKey(task.stepId()) ? task.stepId() : null;
    }

    private String testConfigIn(List<Variable> variables) {
        if (variables == null) {
            return null;
        }
        return variables.stream()
                .filter(variable -> TEST_CONFIG.equalsIgnoreCase(variable.name()))
                .map(Variable::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * The most specific enabled row that matches. Ties are broken by name so that two equally
     * specific rows always resolve the same way — an arbitrary rule, but a stable one, and a
     * simulator that answers differently on different runs is worse than useless.
     */
    private TaskOverride matchingOverride(TaskExecutionRequested task) {
        return overrides.enabled().stream()
                .filter(row -> row.matches(task.workflowDefinitionId(), task.stepId(), task.taskId()))
                .max(Comparator.comparingInt(TaskOverride::specificity)
                        .thenComparing(TaskOverride::toString))
                .orElse(null);
    }
}
