package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retention travels with every copy of a definition — a copy that dropped it would hand the
 * definition's processes to the engine defaults, which may purge sooner — reads and writes as the
 * file wrote it, and is checked like the rest of the definition.
 */
class WorkflowDefinitionRetentionTest {

    private static final ProcessRetention PER_STATUS = ProcessRetention.perStatus(new LinkedHashMap<>(Map.of(
            "completed", "P30D", "error", "P180D", "compensationFailed", "forever")));

    private static final WorkflowDefinition KEPT =
            new WorkflowDefinition("wf", "Wf", 1, null, false, 0, false, null, 0, List.of())
                    .withTags(List.of("billing"))
                    .withRetention(PER_STATUS);

    @Test
    void everyCopyKeepsTheRetention() {
        List<UnaryOperator<WorkflowDefinition>> copies = List.of(
                d -> d.withPaused(true),
                d -> d.withRuntimeStatus(WorkflowStatus.DISABLED),
                d -> d.withDeclaredStatus(WorkflowStatus.ARCHIVED),
                d -> d.withDisabled(true),
                d -> d.withArchived(true),
                d -> d.withRuntimeStateOf(KEPT),
                d -> d.withSteps(List.of()),
                d -> d.withMaxSteps(7),
                d -> d.withLayout(Map.of()),
                d -> d.withProcessLock(null),
                d -> d.withSyncInvocation(null),
                d -> d.withSchedules(List.of()),
                d -> d.withTriggers(List.of()),
                d -> d.withTags(List.of("other")),
                d -> d.withVersion(2));
        for (var copy : copies) {
            assertThat(copy.apply(KEPT).retention()).isEqualTo(PER_STATUS);
        }
        // ...and the retention's own copy keeps everything else, tags included.
        assertThat(KEPT.withRetention(ProcessRetention.of("P1D")).tags()).containsExactly("billing");
    }

    @Test
    void readsAndWritesBothShapesAsTheFileWritesThem() throws Exception {
        var yaml = new YAMLMapper();
        var single = yaml.readValue("""
                id: wf
                name: Wf
                retention: P30D
                steps: []
                """, WorkflowDefinition.class);
        assertThat(single.retention()).isEqualTo(ProcessRetention.of("P30D"));
        assertThat(single.retention().declaredFor(ProcessStatus.ERROR)).contains("P30D");
        assertThat(single.retention().declaredFor(ProcessStatus.RUNNING)).isEmpty();

        var perStatus = yaml.readValue("""
                id: wf
                name: Wf
                retention: {completed: P30D, error: P180D}
                steps: []
                """, WorkflowDefinition.class);
        assertThat(perStatus.retention().declaredFor(ProcessStatus.COMPLETED)).contains("P30D");
        assertThat(perStatus.retention().declaredFor(ProcessStatus.ERROR)).contains("P180D");
        assertThat(perStatus.retention().declaredFor(ProcessStatus.CANCELLED)).isEmpty();

        var json = new ObjectMapper();
        assertThat(json.readTree(json.writeValueAsString(single)).get("retention").asText()).isEqualTo("P30D");
        assertThat(json.readTree(json.writeValueAsString(perStatus)).get("retention").toString())
                .isEqualTo("{\"completed\":\"P30D\",\"error\":\"P180D\"}");
        // Not declared: not written.
        var none = new WorkflowDefinition("wf", "Wf", 1, null, false, 0, false, null, 0, List.of());
        assertThat(json.readTree(json.writeValueAsString(none)).has("retention")).isFalse();
        assertThat(PER_STATUS.toString()).isEqualTo("completed P30D · error P180D · compensationFailed forever");
    }

    @Test
    void anInvalidRetentionIsRefusedByTheDefinitionsInvariants() {
        var bad = KEPT.withRetention(ProcessRetention.of("P0D"));
        assertThatThrownBy(bad::checkInvariants).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention").hasMessageContaining("longer than zero");
        assertThatThrownBy(() -> ProcessRetention.perStatus(Map.of("running", "P1D")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("running");
        assertThatThrownBy(() -> ProcessRetention.fromJson(List.of("P1D")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ProcessRetention.fromJson(null)).isNull();
        KEPT.checkInvariants();
    }
}
