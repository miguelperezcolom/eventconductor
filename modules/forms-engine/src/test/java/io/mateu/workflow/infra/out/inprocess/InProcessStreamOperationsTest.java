package io.mateu.workflow.infra.out.inprocess;

import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.inprocess.InProcessUpstream;
import io.mateu.workflow.worker.WorkerReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** What the forms engine sends when the workflow engine is in the same JVM and there is no broker. */
class InProcessStreamOperationsTest {

    final List<Object> engine = new ArrayList<>();
    final ApplicationEventPublisher applicationEvents = mock(ApplicationEventPublisher.class);

    InProcessStreamOperations withEngine() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("engine", (InProcessUpstream) engine::add);
        return new InProcessStreamOperations(factory.getBeanProvider(InProcessUpstream.class), applicationEvents);
    }

    InProcessStreamOperations withoutEngine() {
        return new InProcessStreamOperations(new StaticListableBeanFactory().getBeanProvider(InProcessUpstream.class),
                applicationEvents);
    }

    @Test
    void theReplyToTheEngineIsHandedToItInProcess() {
        var reply = new TaskStatusChanged("step-1", TaskStatus.COMPLETED, List.of(), "process-1");

        WorkerReply.send(withEngine(), reply);

        assertThat(engine).containsExactly(reply);
        verifyNoInteractions(applicationEvents);
    }

    @Test
    void aLogLineGoesToTheEngineToo() {
        var line = new TaskLogEmitted("step-1", MessageType.Info, "claimed by mia");

        assertThat(withEngine().send("upstream", line, MimeTypeUtils.APPLICATION_JSON)).isTrue();
        assertThat(withEngine().send("upstream", "binder", line)).isTrue();

        assertThat(engine).containsExactly(line, line);
    }

    @Test
    void taskEventsBecomeApplicationEvents() {
        var changed = "the HumanTaskChanged";

        assertThat(withEngine().send(HumanTaskEvents.BINDING, "binder", MessageBuilder.withPayload(changed).build(),
                MimeTypeUtils.APPLICATION_JSON)).isTrue();

        verify(applicationEvents).publishEvent((Object) changed);
        assertThat(engine).isEmpty();
    }

    @Test
    void withoutAnEngineTheReplyIsRefusedAndTheSubmitterTold() {
        var reply = new TaskStatusChanged("step-1", TaskStatus.COMPLETED, List.of(), "process-1");

        assertThat(withoutEngine().send("upstream", reply)).isFalse();
    }

    @Test
    void whatIsNotAnEngineEventOrHasNoDestinationIsRefused() {
        assertThat(withEngine().send("upstream", "not an event")).isFalse();
        assertThat(withEngine().send("somewhere-else", "anything")).isFalse();
        assertThat(engine).isEmpty();
    }

    @Test
    void anEngineThatFailsTheReplyFailsTheSend() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("engine", (InProcessUpstream) event -> {
            throw new IllegalStateException("no such step");
        });
        var operations = new InProcessStreamOperations(factory.getBeanProvider(InProcessUpstream.class), applicationEvents);

        assertThatThrownBy(() -> operations.send("upstream",
                new TaskStatusChanged("step-1", TaskStatus.COMPLETED, List.of(), "process-1")))
                .hasMessage("no such step");
    }
}
