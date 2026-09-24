package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.domain.aggregates.ProcessReply;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SyncInvocationControllerTest {

    @Test
    void readsTheWaitFromPrefer() {
        assertThat(SyncInvocationController.waitOf(null)).isNull();
        assertThat(SyncInvocationController.waitOf("wait=10")).isEqualTo(Duration.ofSeconds(10));
        assertThat(SyncInvocationController.waitOf("respond-async, wait=3")).isEqualTo(Duration.ofSeconds(3));
        assertThat(SyncInvocationController.waitOf("return=minimal")).isNull();
    }

    @Test
    void mapsOutcomesToStatuses() {
        assertThat(SyncInvocationController.statusOf(ProcessReply.replied("r", "{}"))).isEqualTo(HttpStatus.OK);
        assertThat(SyncInvocationController.statusOf(
                ProcessReply.of(ProcessReply.Outcome.COMPLETED_WITHOUT_REPLY, null, null))).isEqualTo(HttpStatus.OK);
        for (var outcome : new ProcessReply.Outcome[]{ProcessReply.Outcome.FAILED, ProcessReply.Outcome.COMPENSATED,
                ProcessReply.Outcome.COMPENSATION_FAILED, ProcessReply.Outcome.CANCELLED}) {
            assertThat(SyncInvocationController.statusOf(ProcessReply.of(outcome, null, "x")))
                    .as(outcome.name()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }
}
