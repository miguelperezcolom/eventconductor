package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.dtos.events.integration.RuleDeleted;
import io.mateu.workflow.dtos.events.integration.RulePublished;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka mode: every catalogue change goes out on the {@code rules} binding, so remote runtimes drop
 * or refresh their cached copy. A publication carries the rule itself, so they need not fetch it.
 */
class KafkaRuleCatalogEventPublisherTest {

    final StreamBridge bridge = mock(StreamBridge.class);
    final RuleJsonMapper mapper = mock(RuleJsonMapper.class);
    final KafkaRuleCatalogEventPublisher publisher = new KafkaRuleCatalogEventPublisher(bridge, mapper);

    @Test
    void aPublishedRuleIsAnnouncedWithItsCanonicalJson() {
        var rule = new Rule("discount", "Discount", null, RuleType.EXPRESSION, 3, 0, List.of(), "true",
                List.of(new Assignment("ok", "true")), null, null, null, null);
        when(mapper.toJson(rule)).thenReturn("{\"id\":\"discount\"}");

        publisher.published(rule);

        verify(bridge).send("rules", new RulePublished("discount", "Discount", 3, "{\"id\":\"discount\"}"));
    }

    @Test
    void aDeletedRuleIsAnnouncedById() {
        publisher.deleted("discount");
        verify(bridge).send(eq("rules"), eq(new RuleDeleted("discount")));
    }
}
