package io.mateu.workflow.worker.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the transport-agnostic half of the worker: it gathers the {@link TaskRegistration} beans a
 * service exposes into a {@link TaskRegistry} and settles on an {@link ObjectMapper}. A transport
 * adapter (worker-kafka, worker-embedded) contributes the {@link TaskReplySink} and
 * {@link Cancellations}, and assembles the {@link TaskDispatcher} from these pieces — so this
 * configuration stays broker-free and loads on its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TaskRegistry taskRegistry(ObjectProvider<TaskRegistration> registrations) {
        return new TaskRegistry(registrations.orderedStream()
                .map(r -> (TaskRegistration<?, ?>) r)
                .toList());
    }

    @Bean("workerApiObjectMapper")
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper workerApiObjectMapper() {
        return new ObjectMapper();
    }
}
