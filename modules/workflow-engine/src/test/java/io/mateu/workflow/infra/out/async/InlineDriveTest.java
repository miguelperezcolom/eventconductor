package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InlineDriveTest {

    private static final InlineDrive.Context DRIVING_P1 = new InlineDrive.Context("p1", "pod-a", LocalDateTime.now());

    @Test
    void outsideADriveNothingIsClaimed() {
        assertThat(InlineDrive.claimFor(new ProcessCreated("p1", List.of()))).isNull();
    }

    @Test
    void theDrivenProcessesOwnEventsAndItsLogLinesAreClaimed() {
        InlineDrive.run(DRIVING_P1, () -> {
            assertThat(InlineDrive.claimFor(new ProcessCreated("p1", List.of()))).isEqualTo(DRIVING_P1);
            assertThat(InlineDrive.claimFor(new TaskLogEmitted("se-1", MessageType.Info, "hi"))).isEqualTo(DRIVING_P1);
            return null;
        });
    }

    @Test
    void anotherProcessesEventsAndTheOnesThatMayLeaveTheShardAreNot() {
        InlineDrive.run(DRIVING_P1, () -> {
            assertThat(InlineDrive.claimFor(new ProcessCreated("p2", List.of()))).isNull();
            assertThat(InlineDrive.claimFor(new MessageReceived("m", "k", List.of()))).isNull();
            return null;
        });
    }

    @Test
    void aNestedDriveRestoresTheOuterOne() {
        var inner = new InlineDrive.Context("p2", "pod-a", LocalDateTime.now());
        InlineDrive.run(DRIVING_P1, () -> {
            InlineDrive.run(inner, () -> {
                assertThat(InlineDrive.claimFor(new ProcessCreated("p2", List.of()))).isEqualTo(inner);
                return null;
            });
            assertThat(InlineDrive.claimFor(new ProcessCreated("p1", List.of()))).isEqualTo(DRIVING_P1);
            return null;
        });
        assertThat(InlineDrive.claimFor(new ProcessCreated("p1", List.of()))).isNull();
    }
}
