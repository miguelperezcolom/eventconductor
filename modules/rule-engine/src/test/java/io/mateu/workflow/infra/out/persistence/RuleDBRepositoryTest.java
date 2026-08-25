package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The adapter between a {@link Rule} and its row, with the real JSON mapper behind it.
 *
 * <p>A rule is stored twice over: as canonical JSON, which is what is read back, and as four
 * columns — id, name, type, version — which are what the listing and the catalogue queries use.
 * Nothing keeps the two halves in step but this class, so the round trip is worth pinning, and so
 * is the column the JSON does not obviously produce: {@code type} is written as the enum's
 * <em>label</em>, `expression`, not as its Java name.
 */
class RuleDBRepositoryTest {

    private final RuleJsonMapper mapper = new RuleJsonMapper();
    private final Map<String, RuleEntity> stored = new HashMap<>();

    private RuleEntityRepository entities;
    private RuleDBRepository repository;

    @BeforeEach
    void setUp() {
        entities = mock(RuleEntityRepository.class);
        when(entities.save(any(RuleEntity.class))).thenAnswer(call -> {
            RuleEntity entity = call.getArgument(0);
            stored.put(entity.getId(), entity);
            return entity;
        });
        when(entities.findById(any())).thenAnswer(call ->
                Optional.ofNullable(stored.get(call.getArgument(0).toString())));
        when(entities.findAll()).thenAnswer(call -> List.copyOf(stored.values()));
        repository = new RuleDBRepository(entities, mapper);
    }

    @Test
    void an_expression_rule_survives_the_round_trip_through_its_json_column() {
        var rule = new Rule("discount", "Discount", "10% over 100", RuleType.EXPRESSION, 3, 5,
                List.of("pricing", "eu"), "order.total > 100",
                List.of(new Assignment("discount", "10")),
                null, null, null, null);

        assertThat(repository.save(rule)).isEqualTo("discount");

        var read = repository.findById("discount").orElseThrow();
        assertThat(read.name()).isEqualTo("Discount");
        assertThat(read.description()).isEqualTo("10% over 100");
        assertThat(read.type()).isEqualTo(RuleType.EXPRESSION);
        assertThat(read.version()).isEqualTo(3);
        assertThat(read.salience()).isEqualTo(5);
        assertThat(read.tags()).containsExactly("pricing", "eu");
        assertThat(read.when()).isEqualTo("order.total > 100");
        assertThat(read.then()).hasSize(1);
    }

    @Test
    void the_queryable_columns_are_written_beside_the_json_and_the_type_is_its_label() {
        repository.save(new Rule("discount", "Discount", "", RuleType.DECISION_TABLE, 2, 0,
                List.of(), null, null, List.of("total"), List.of("discount"), List.of(), null));

        var entity = ArgumentCaptor.forClass(RuleEntity.class);
        verify(entities).save(entity.capture());
        assertThat(entity.getValue().getId()).isEqualTo("discount");
        assertThat(entity.getValue().getName()).isEqualTo("Discount");
        assertThat(entity.getValue().getVersion()).isEqualTo(2);
        // `decision-table`, the wire label — not DECISION_TABLE. Anything querying the catalogue by
        // type is matching on what the definitions themselves say.
        assertThat(entity.getValue().getType()).isEqualTo("decision-table");
        assertThat(entity.getValue().getRuleJson()).contains("decision-table");
    }

    @Test
    void a_rule_with_no_type_is_stored_rather_than_refused() {
        // Half-written definitions reach this layer — the catalogue UI saves as you type. A null
        // here must leave a null column, not a NullPointerException on the way in.
        repository.save(new Rule("draft", "Draft", "", null, 1, 0, List.of(), null, null,
                null, null, null, null));

        var entity = ArgumentCaptor.forClass(RuleEntity.class);
        verify(entities).save(entity.capture());
        assertThat(entity.getValue().getType()).isNull();
    }

    @Test
    void an_id_nobody_stored_is_absent() {
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    void findAll_reads_every_rule_back_from_its_json() {
        repository.save(new Rule("r1", "One", "", RuleType.EXPRESSION, 1, 0, List.of(),
                "true", List.of(), null, null, null, null));
        repository.save(new Rule("r2", "Two", "", RuleType.EXPRESSION, 1, 0, List.of(),
                "false", List.of(), null, null, null, null));

        assertThat(repository.findAll()).extracting(Rule::name)
                .containsExactlyInAnyOrder("One", "Two");
    }

    @Test
    void deleting_passes_the_ids_straight_through() {
        repository.deleteAllById(List.of("r1", "r2"));

        verify(entities).deleteAllById(anyList());
    }
}
