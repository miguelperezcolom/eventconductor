package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.application.services.SensitiveFixtures;
import io.mateu.workflow.application.services.VariableProtection;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.Optional;

import static io.mateu.workflow.application.services.SensitiveFixtures.CARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What reaches the broker: the sensitive variables of every message the engine publishes, sealed. */
class KafkaPublishersSealSensitiveVariablesTest {

    final StreamBridge streamBridge = mock(StreamBridge.class);
    final io.mateu.workflow.crypto.VariableEncryption keys = SensitiveFixtures.keys();
    final VariableProtection protection;

    KafkaPublishersSealSensitiveVariablesTest() {
        var policies = SensitiveFixtures.policies(keys, null, null);
        policies.of(SensitiveFixtures.process("p1"));
        protection = new VariableProtection(policies);
        when(streamBridge.send(anyString(), any())).thenReturn(true);
    }

    private DomainEvent sent() {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge).send(anyString(), captor.capture());
        var payload = captor.getValue();
        return (DomainEvent) (payload instanceof Message<?> message ? message.getPayload() : payload);
    }

    private static List<Variable> task(String card) {
        return List.of(new Variable("cardNumber", card), new Variable("amount", "120"));
    }

    @Test
    void aTaskForAKafkaWorkerCarriesItsCardNumberSealed() {
        var index = mock(ProcessIndexRepository.class);
        when(index.findByProcessId("p1")).thenReturn(Optional.empty());
        var downstream = new KafkaDownstreamEventPublisher(streamBridge, WorkflowTracing.NOOP, protection);
        downstream.publish(new TaskExecutionRequested("se", "p1", "payment", "charge", "charge@1", task(CARD)), "work");
        var sent = (TaskExecutionRequested) sent();
        assertThat(sent.variables().get(0).value()).startsWith("ec:enc:v1:test-key:").doesNotContain(CARD);
        assertThat(keys.open(sent.variables().get(0).value())).isEqualTo(CARD);
        assertThat(sent.variables().get(1).value()).isEqualTo("120");
    }

    @Test
    void commandsUpstreamEventsAndMessagesAreSealedToo() {
        var index = mock(ProcessIndexRepository.class);
        when(index.findByProcessId("p1")).thenReturn(Optional.empty());
        new KafkaCommandPublisher(streamBridge, WorkflowTracing.NOOP, index, protection)
                .publish(new TaskStatusChanged("se", TaskStatus.COMPLETED, task(CARD), "p1"));
        assertThat(((TaskStatusChanged) sent()).variables().getFirst().value()).doesNotContain(CARD);
    }

    @Test
    void upstreamAndIngress() {
        new KafkaUpstreamEventPublisher(streamBridge, WorkflowTracing.NOOP, protection)
                .publish(new TaskStatusChanged("se", TaskStatus.COMPLETED, task(CARD), "p1"));
        assertThat(((TaskStatusChanged) sent()).variables().getFirst().value()).doesNotContain(CARD);
    }

    @Test
    void ingress() {
        new KafkaIngressPublisher(streamBridge, WorkflowTracing.NOOP, protection)
                .publishToShard(new TaskStatusChanged("se", TaskStatus.COMPLETED, task(CARD), "p1"), "s1");
        assertThat(((TaskStatusChanged) sent()).variables().getFirst().value()).doesNotContain(CARD);
    }

    @Test
    void messagesGoByEveryDefinitionsUnion() {
        var definitions = mock(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class);
        when(definitions.findAll()).thenReturn(List.of(SensitiveFixtures.definition()));
        var protection = new VariableProtection(SensitiveFixtures.policies(keys, null, definitions));
        new KafkaMessagePublisher(streamBridge, WorkflowTracing.NOOP, protection)
                .publish(new MessageReceived("paid", "k", task(CARD)));
        assertThat(((MessageReceived) sent()).variables().getFirst().value()).doesNotContain(CARD);
    }

    @Test
    void aPublisherBuiltWithoutProtectionSendsAsItIs() {
        var event = new TaskStatusChanged("se", TaskStatus.COMPLETED, task(CARD), "p1");
        assertThat(VariableProtectionSupport.seal(null, event)).isSameAs(event);
    }
}
