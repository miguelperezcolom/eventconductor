package io.mateu.workflow.worker.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.mateu.workflow.worker.api.TaskRegistration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Registers the built-in {@code llm-call@1} and {@code agent-turn@1} tasks wherever this module is on the classpath: the
 * transport (worker-embedded in the engine's pod, worker-kafka in a worker deployment) collects it
 * like any task. {@code workflow.llm.enabled=false} leaves it out.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "workflow.llm.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LlmWorkerProperties.class)
public class LlmWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatModelFactory llmChatModelFactory(ObjectProvider<ObservationRegistry> observations) {
        return new SpringAiChatModelFactory(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmCallHandler llmCallHandler(LlmWorkerProperties properties, ChatModelFactory factory,
                                         ObjectProvider<MeterRegistry> registry) {
        return new LlmCallHandler(properties, factory, new LlmCallMetrics(registry.getIfAvailable()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentTurnHandler agentTurnHandler(LlmWorkerProperties properties, ChatModelFactory factory,
                                             ObjectProvider<MeterRegistry> registry) {
        return new AgentTurnHandler(properties, factory, new LlmCallMetrics(registry.getIfAvailable()));
    }

    /** One model turn of an AGENT_CALL step — same topic and connections as llm-call. */
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TaskRegistration agentTurnTaskRegistration(AgentTurnHandler handler, LlmWorkerProperties properties) {
        return new TaskRegistration<>("agent-turn", 1, properties.getTopic(), AgentTurnHandler.Input.class,
                (Class<Map<String, Object>>) (Class) Map.class, handler);
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TaskRegistration llmCallTaskRegistration(LlmCallHandler handler, LlmWorkerProperties properties) {
        return new TaskRegistration<>("llm-call", 1, properties.getTopic(), LlmCallHandler.Input.class,
                (Class<Map<String, Object>>) (Class) Map.class, handler);
    }
}
