package io.mateu.workflow.infra.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The static shard list parses a trimmed CSV, and empty/blank means not sharded. */
class ConfigShardRegistryTest {

    @Test
    void parsesATrimmedCommaSeparatedList() {
        assertThat(new ConfigShardRegistry(" s0 , s1 ,s2 ").activeShards())
                .containsExactly("s0", "s1", "s2");
    }

    @Test
    void blankMeansNotSharded() {
        assertThat(new ConfigShardRegistry("").activeShards()).isEmpty();
        assertThat(new ConfigShardRegistry("   ").activeShards()).isEmpty();
    }
}
