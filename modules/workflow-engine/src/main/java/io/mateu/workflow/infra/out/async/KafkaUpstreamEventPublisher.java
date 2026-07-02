package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
public class KafkaUpstreamEventPublisher implements UpstreamEventPublisher {

    private final StreamBridge streamBridge;

    @Override
    public void publish(DomainEvent event) {
        streamBridge.send("upstream", event);
    }
}
