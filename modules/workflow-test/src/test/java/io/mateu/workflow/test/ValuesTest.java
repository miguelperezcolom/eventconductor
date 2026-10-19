package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuesTest {

    @Test
    void valuesAreStoredAsTheWorkerSdkStoresThem() {
        assertThat(Values.text(null)).isNull();
        assertThat(Values.text("007")).isEqualTo("007");
        assertThat(Values.text(3)).isEqualTo("3");
        assertThat(Values.text(Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(Values.text(List.of(true))).isEqualTo("[true]");
    }

    @Test
    void matchingComparesTextAsTextAndTheRestAsJson() {
        assertThat(Values.matches("3", 3)).isTrue();
        assertThat(Values.matches("3.0", 3)).isTrue();
        assertThat(Values.matches("{\"a\": 1}", Map.of("a", 1))).isTrue();
        assertThat(Values.matches("007", "7")).isFalse();
        assertThat(Values.matches("abc", 1)).isFalse();
        assertThat(Values.matches("\"x\"", "y")).isFalse();
        assertThat(Values.matches(null, null)).isTrue();
        assertThat(Values.matches(null, "x")).isFalse();
        assertThat(Values.parsed("[1")).isEqualTo("[1");
        assertThatThrownBy(() -> Values.tree("{")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTestClockOnlyMovesWhenTold() {
        var clock = new TestClock(Instant.parse("2030-01-01T00:00:00Z"), ZoneId.of("UTC"));
        assertThat(clock.withZone(ZoneId.of("Europe/Madrid")).instant()).isEqualTo(clock.instant());
        clock.advance(Duration.ofHours(1));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2030-01-01T01:00:00Z"));
        clock.advanceTo(Instant.parse("2029-01-01T00:00:00Z"));
        assertThat(clock.now().getYear()).isEqualTo(2030);
    }

    @Test
    void taskIdsLoseTheirVersion() {
        assertThat(StubbingTaskExecutor.baseTaskId("charge-card@2")).isEqualTo("charge-card");
        assertThat(StubbingTaskExecutor.baseTaskId("a@b")).isEqualTo("a@b");
        assertThat(StubbingTaskExecutor.baseTaskId(null)).isEmpty();
    }

    @Test
    void failuresReadLikeTheWorkerSdks() {
        assertThat(new TaskOutcome.Fail("X", null, true).reason()).isEqualTo("X");
        assertThat(new TaskOutcome.Fail("X", "why", true).reason()).isEqualTo("X: why");
        assertThat(new TaskOutcome.Fail("X", null, false).reason()).startsWith("[non-retryable]");
        assertThat(TaskOutcome.fail("X", "m", false)).isEqualTo(new TaskOutcome.Fail("X", "m", false));
        assertThat(TaskOutcome.complete(null)).isEqualTo(new TaskOutcome.Complete(Map.of()));
    }
}
