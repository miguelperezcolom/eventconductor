package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.tasks.TaskContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The task-reference resolver, pinned on a fake store: a bare id is pinned to the latest version,
 * an explicit version is honoured, the contract's topic is the default while an explicit step topic
 * wins, an unknown reference fails loud, and steps that reference no contract are left untouched.
 */
class TaskReferenceResolverTest {

    /** Version-aware fake store. */
    private final Map<String, TreeMap<Integer, TaskContract>> store = new ConcurrentHashMap<>();

    private final TaskContractRepository repository = new TaskContractRepository() {
        public Optional<TaskContract> find(String id, int version) {
            var v = store.get(id);
            return v == null ? Optional.empty() : Optional.ofNullable(v.get(version));
        }
        public Optional<TaskContract> findLatest(String id) {
            var v = store.get(id);
            return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.lastEntry().getValue());
        }
        public List<TaskContract> findAll() {
            return store.values().stream().flatMap(v -> v.values().stream()).toList();
        }
        public String save(TaskContract c) {
            store.computeIfAbsent(c.id(), k -> new TreeMap<>()).put(c.version(), c);
            return c.ref();
        }
    };

    private final TaskReferenceResolver resolver = new TaskReferenceResolver(repository);

    private void contract(String id, int version, String topic) {
        repository.save(new TaskContract(id, version, "g", topic, null, null, null, null));
    }

    private Step action(String id, String task, String topic) {
        return new Step(id, "wd-1", StepType.ACTION, id, null, "start", null, null, false, topic,
                null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null)
                .withTask(task);
    }

    private WorkflowDefinition wf(Step... steps) {
        return new WorkflowDefinition("wd-1", "wf", 1, null, false, 0, false, null, 0, List.of(steps));
    }

    @Test
    void a_bare_id_is_pinned_to_the_latest_version() {
        contract("greet", 1, "greetings");
        contract("greet", 2, "greetings");

        var resolved = resolver.resolve(wf(action("s", "greet", null)));

        assertThat(resolved.steps().get(0).task()).isEqualTo("greet@2");
    }

    @Test
    void an_explicit_version_is_honoured() {
        contract("greet", 1, "greetings");
        contract("greet", 2, "greetings");

        var resolved = resolver.resolve(wf(action("s", "greet@1", null)));

        assertThat(resolved.steps().get(0).task()).isEqualTo("greet@1");
    }

    @Test
    void the_contract_topic_is_the_default_but_an_explicit_step_topic_wins() {
        contract("greet", 1, "greetings");

        assertThat(resolver.resolve(wf(action("s", "greet", null))).steps().get(0).topic())
                .isEqualTo("greetings");
        assertThat(resolver.resolve(wf(action("s", "greet", "own-topic"))).steps().get(0).topic())
                .isEqualTo("own-topic");
    }

    @Test
    void an_unknown_reference_fails_loud() {
        assertThatThrownBy(() -> resolver.resolve(wf(action("s", "nope", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");

        contract("greet", 1, null);
        assertThatThrownBy(() -> resolver.resolve(wf(action("s", "greet@9", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greet@9");
    }

    @Test
    void a_step_that_references_no_contract_is_untouched() {
        var start = new Step("start", "wd-1", StepType.START, "start", null, null, null, null, false,
                null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var plainAction = action("s", null, "t");
        var resolved = resolver.resolve(wf(start, plainAction));
        assertThat(resolved.steps().get(1).task()).isNull();
        assertThat(resolved.steps().get(1).topic()).isEqualTo("t");
    }
}
