package io.mateu.workflow.controlplaneservice.infra.in.async;

import io.mateu.workflow.controlplaneservice.application.usecases.scrape.ScrapeCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.scrape.ScrapeUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WorkerKafkaConsumerConfig {

    final ScrapeUseCase scrapeUseCase;

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent() { // Cambiado de Consumer a Function
        return event -> {
          log.info("Received event: " + event);
          if (event instanceof TaskExecutionRequested taskExecutionRequested) {
              if (taskExecutionRequested.workflowDefinitionId().equals("52ea7ab0-be39-44e4-af06-88dd61f2b0cd")
                      && taskExecutionRequested.stepId().equals("capturar")) {
                  scrapeUseCase.handle(new ScrapeCommand(taskExecutionRequested.variables().stream()
                          .filter(variable -> "siteId".equals(variable.name()))
                          .findAny().orElseThrow().value(),
                          taskExecutionRequested.taskExecutionId()));
              }
          }
        };
    }

}
