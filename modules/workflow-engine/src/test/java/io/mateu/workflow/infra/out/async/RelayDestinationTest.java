package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static io.mateu.workflow.infra.out.async.RelayDestination.bindingFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three lines that decide where every state transition in the fleet ends up, so each combination is
 * pinned — including the two that must NOT divert, which are the ones a single-cluster deployment
 * depends on being left alone.
 */
class RelayDestinationTest {

    private final MessageReceived message = new MessageReceived("payment-confirmed", "k1", List.of());
    private final ProcessStatusChanged statusChanged = new ProcessStatusChanged(
            "p1", "k1", "wd-1", 1, "RUNNING", 40, LocalDateTime.now(), LocalDateTime.now(), null,
            LocalDateTime.now(), "s0");
    private final ProcessCreated created = new ProcessCreated("p1", List.of());

    @Test
    void singleClusterSendsEverythingToItsOwnOutbox() {
        assertThat(bindingFor(message, false, false)).isEqualTo("outbox");
        assertThat(bindingFor(statusChanged, false, false)).isEqualTo("outbox");
        assertThat(bindingFor(created, false, false)).isEqualTo("outbox");
    }

    @Test
    void crossShardMessagingDivertsOnlyMessages() {
        assertThat(bindingFor(message, true, false)).isEqualTo("messages");
        // A status change is not a message: with the projector in-process it must stay on `outbox`,
        // or the shard's own read model silently stops being maintained.
        assertThat(bindingFor(statusChanged, true, false)).isEqualTo("outbox");
        assertThat(bindingFor(created, true, false)).isEqualTo("outbox");
    }

    @Test
    void remoteProjectionDivertsOnlyStatusChanges() {
        assertThat(bindingFor(statusChanged, false, true)).isEqualTo("processIndex");
        assertThat(bindingFor(message, false, true)).isEqualTo("outbox");
        assertThat(bindingFor(created, false, true)).isEqualTo("outbox");
    }

    @Test
    void aShardedFleetWithARemoteProjectorDivertsBothAndNothingElse() {
        assertThat(bindingFor(message, true, true)).isEqualTo("messages");
        assertThat(bindingFor(statusChanged, true, true)).isEqualTo("processIndex");
        assertThat(bindingFor(created, true, true)).isEqualTo("outbox");
    }
}
