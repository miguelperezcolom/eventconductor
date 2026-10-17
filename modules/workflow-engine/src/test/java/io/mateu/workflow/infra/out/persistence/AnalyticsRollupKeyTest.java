package io.mateu.workflow.infra.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rollup rows' surrogate key: printable (PostgreSQL refuses NUL in text) and collision-free. */
class AnalyticsRollupKeyTest {

    @Test
    void everyCharacterIsPrintable() {
        var key = AnalyticsRollupProjector.key("order", "reserve", "2026-01-10", "COMPLETED");
        assertThat(key).isEqualTo("5:order:7:reserve:10:2026-01-10:COMPLETED");
        assertThat(key.chars()).noneMatch(Character::isISOControl);
    }

    @Test
    void theLengthPrefixKeepsDistinctPartsApart() {
        assertThat(AnalyticsRollupProjector.key("a:b", "c"))
                .isNotEqualTo(AnalyticsRollupProjector.key("a", "b:c"));
        assertThat(AnalyticsRollupProjector.key("a", "b", "c"))
                .isNotEqualTo(AnalyticsRollupProjector.key("a", "b:c"));
    }

    @Test
    void aMissingDefinitionIdStillMakesAKey() {
        assertThat(AnalyticsRollupProjector.key(null, "2026-01-10")).isEqualTo("4:null:2026-01-10");
    }
}
