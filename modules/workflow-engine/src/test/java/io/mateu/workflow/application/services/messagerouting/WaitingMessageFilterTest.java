package io.mateu.workflow.application.services.messagerouting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The layer-3 filter's contract: it may say "maybe" for a pair no step waits for (a false positive,
 * costing a query) but must never say "no" for one a step does wait for (a false negative, losing a
 * message). These pin that, plus the transparency of the disabled and not-yet-populated states.
 */
class WaitingMessageFilterTest {

    private WaitingMessageFilter enabled() {
        return new WaitingMessageFilter(true, 10_000, 0.01);
    }

    @Test
    void whenDisabledItAlwaysSaysMaybe() {
        var filter = new WaitingMessageFilter(false, 10_000, 0.01);
        filter.rebuild(List.<String[]>of(new String[] {"payment", "O-1"}));

        // Transparent: never skips a query, so behaviour is exactly as before it existed.
        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isTrue();
        assertThat(filter.mightBeWaitingFor("anything", "at-all")).isTrue();
        assertThat(filter.isReady()).isFalse();
    }

    @Test
    void beforeItIsPopulatedItSaysMaybe() {
        // Until the first rebuild lands there is no answer to trust, so every message runs the query.
        var filter = enabled();

        assertThat(filter.isReady()).isFalse();
        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isTrue();
    }

    @Test
    void afterRebuildItKnowsTheWaitingPairs() {
        var filter = enabled();
        filter.rebuild(List.<String[]>of(new String[] {"payment", "O-1"}, new String[] {"shipment", "O-2"}));

        assertThat(filter.isReady()).isTrue();
        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isTrue();
        assertThat(filter.mightBeWaitingFor("shipment", "O-2")).isTrue();
        // A pair nobody waits for is (almost certainly) ruled out — the whole point.
        assertThat(filter.mightBeWaitingFor("payment", "O-999")).isFalse();
    }

    @Test
    void anIncrementalAddIsVisibleOnceReady() {
        var filter = enabled();
        filter.rebuild(List.of()); // ready, empty
        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isFalse();

        filter.add("payment", "O-1");

        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isTrue();
    }

    @Test
    void rebuildShedsPairsThatStoppedWaiting() {
        var filter = enabled();
        filter.rebuild(List.<String[]>of(new String[] {"payment", "O-1"}));
        filter.add("payment", "O-2");
        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isTrue();
        assertThat(filter.mightBeWaitingFor("payment", "O-2")).isTrue();

        // O-1 has stopped waiting; only O-2 remains. The rebuild is what forgets O-1.
        filter.rebuild(List.<String[]>of(new String[] {"payment", "O-2"}));

        assertThat(filter.mightBeWaitingFor("payment", "O-1")).isFalse();
        assertThat(filter.mightBeWaitingFor("payment", "O-2")).isTrue();
    }

    @Test
    void neverSaysNoForAWaitingPair() {
        var filter = enabled();
        var pairs = IntStream.range(0, 5_000)
                .mapToObj(i -> new String[] {"msg-" + (i % 7), "key-" + i})
                .toList();
        filter.rebuild(pairs);

        // The invariant that makes the skip safe: no false negatives.
        assertThat(pairs).allSatisfy(p -> assertThat(filter.mightBeWaitingFor(p[0], p[1])).isTrue());
    }
}
