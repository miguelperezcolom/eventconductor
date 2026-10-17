package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TagRulesTest {

    @Test
    void aTagIsAShortLabel() {
        assertThat(TagRules.isValidTag("billing")).isTrue();
        assertThat(TagRules.isValidTag("team:payments")).isTrue();
        assertThat(TagRules.isValidTag("eu/west-1_v2.0")).isTrue();
        assertThat(TagRules.isValidTag(" eu ")).isTrue();
        assertThat(TagRules.isValidTag("")).isFalse();
        assertThat(TagRules.isValidTag(null)).isFalse();
        assertThat(TagRules.isValidTag("-leading")).isFalse();
        assertThat(TagRules.isValidTag("with space")).isFalse();
        assertThat(TagRules.isValidTag("x".repeat(51))).isFalse();
    }

    @Test
    void violationsNameEachBadTagAndEachRepeatOnce() {
        assertThat(TagRules.violations(null, "tags")).isEmpty();
        assertThat(TagRules.violations(List.of("billing", "eu"), "tags")).isEmpty();
        assertThat(TagRules.violations(Arrays.asList("billing", "Billing", "bad tag", null), "tags"))
                .hasSize(3)
                .anySatisfy(v -> assertThat(v).startsWith("tags: tag 'Billing' is declared more than once"))
                .anySatisfy(v -> assertThat(v).contains("'bad tag' is not a valid tag"))
                .anySatisfy(v -> assertThat(v).contains("'null' is not a valid tag"));
    }
}
