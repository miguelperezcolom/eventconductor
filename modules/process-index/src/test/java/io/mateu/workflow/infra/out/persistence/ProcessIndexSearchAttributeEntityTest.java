package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.search.SearchAttributeType;
import io.mateu.workflow.search.SearchAttributeValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The attribute row's key and its round trip to a value. */
class ProcessIndexSearchAttributeEntityTest {

    @Test
    void theKeyIsTheProcessAndTheName() {
        var key = new ProcessIndexSearchAttributeEntity.Key("p1", "amount");
        assertThat(key).isEqualTo(new ProcessIndexSearchAttributeEntity.Key("p1", "amount"))
                .hasSameHashCodeAs(new ProcessIndexSearchAttributeEntity.Key("p1", "amount"))
                .isNotEqualTo(new ProcessIndexSearchAttributeEntity.Key("p1", "other"))
                .isNotEqualTo(new ProcessIndexSearchAttributeEntity.Key("p2", "amount"))
                .isNotEqualTo("p1");
        assertThat(new ProcessIndexSearchAttributeEntity.Key()).isEqualTo(new ProcessIndexSearchAttributeEntity.Key());
        var value = SearchAttributeValue.of("amount", SearchAttributeType.NUMBER, "12").orElseThrow();
        var entity = new ProcessIndexSearchAttributeEntity("p1", value);
        assertThat(entity.getProcessId()).isEqualTo("p1");
        assertThat(entity.toValue()).isEqualTo(value);
    }
}
