package io.mateu.workflow.controlplaneservice.infra.in.async;

import io.mateu.workflow.controlplaneservice.application.usecases.createrelease.CreateReleaseCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.createrelease.CreateReleaseUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.createrelease.UploadToR2Command;
import io.mateu.workflow.controlplaneservice.application.usecases.createrelease.UploadToR2UseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.deploy.*;
import io.mateu.workflow.controlplaneservice.application.usecases.scrape.DownloadAssetsCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.scrape.DownloadAssetsUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.scrape.ScrapeCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.scrape.ScrapeUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static io.mateu.core.infra.JsonSerializer.listFromJson;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WorkerKafkaConsumerConfig {

    final ScrapeUseCase scrapeUseCase;
    final DownloadAssetsUseCase downloadAssetsUseCase;
    final CreateReleaseUseCase createReleaseUseCase;
    final UploadToR2UseCase uploadToR2UseCase;
    final DeployUseCase deployUseCase;
    final VerifyDeploymentUseCase verifyDeploymentUseCase;
    final SetPlannedReleaseUseCase setPlannedReleaseUseCase;
    final UpdateRoutesUseCase updateRoutesUseCase;

    @Bean
    public Consumer<DomainEvent> consumeWorkerEvent() { // Cambiado de Consumer a Function
        return event -> {
          log.info("Received event: " + event);
          if (event instanceof TaskExecutionRequested taskExecutionRequested) {

              if (taskExecutionRequested.stepId().equals("capturar")) {
                  new Thread(() -> scrapeUseCase.handle(new ScrapeCommand(taskExecutionRequested.variables().stream()
                          .filter(variable -> "siteId".equals(variable.name()))
                          .findAny().orElseThrow().value(),
                          taskExecutionRequested.taskExecutionId()))).start();
              }

              if (taskExecutionRequested.stepId().equals("download")) {
                  new Thread(() -> downloadAssetsUseCase.handle(new DownloadAssetsCommand(taskExecutionRequested.variables().stream()
                          .filter(variable -> "siteId".equals(variable.name()))
                          .findAny().orElseThrow().value(),
                          taskExecutionRequested.taskExecutionId()))).start();
              }

              if (taskExecutionRequested.stepId().equals("create-content")) {
                  new Thread(() -> createReleaseUseCase.handle(new CreateReleaseCommand(taskExecutionRequested.variables().stream()
                          .filter(variable -> "name".equals(variable.name()))
                          .findAny().orElseThrow().value(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "siteId".equals(variable.name()))
                                  .findAny().orElseThrow().value(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "userId".equals(variable.name()))
                                  .findAny().orElseThrow().value(),
                          taskExecutionRequested.taskExecutionId()))).start();
              }

              if (taskExecutionRequested.stepId().equals("upload")) {
                  new Thread(() -> uploadToR2UseCase.handle(new UploadToR2Command(taskExecutionRequested.variables().stream()
                          .filter(variable -> "name".equals(variable.name()))
                          .findAny().orElseThrow().value(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "siteId".equals(variable.name()))
                                  .findAny().orElseThrow().value(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "userId".equals(variable.name()))
                                  .findAny().orElseThrow().value(),
                          taskExecutionRequested.taskExecutionId(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "releaseId".equals(variable.name()))
                                  .findAny().orElseThrow().value()))).start();
              }

              if (taskExecutionRequested.stepId().equals("update-script")) {
                  new Thread(() -> deployUseCase.handle(new DeployCommand(
                        taskExecutionRequested.taskExecutionId(),
                        List.of(),
                        taskExecutionRequested.variables().stream()
                                .filter(variable -> "releaseId".equals(variable.name()))
                                .findAny().orElseThrow().value(),
                        taskExecutionRequested.variables().stream()
                                .filter(variable -> "deploymentId".equals(variable.name()))
                                .findAny().orElseThrow().value()
                        ))).start();
              }

              if (taskExecutionRequested.stepId().equals("verify")) {
                  new Thread(() -> verifyDeploymentUseCase.handle(new DeployCommand(taskExecutionRequested.taskExecutionId(),
                            List.of(),
                            taskExecutionRequested.variables().stream()
                                    .filter(variable -> "releaseId".equals(variable.name()))
                                    .findAny().orElseThrow().value(),
                            taskExecutionRequested.variables().stream()
                                    .filter(variable -> "deploymentId".equals(variable.name()))
                                    .findAny().orElseThrow().value()))).start();
              }


              if (taskExecutionRequested.stepId().equals("update-releases")) {
                  new Thread(() -> updateRoutesUseCase.handle(new DeployCommand(taskExecutionRequested.taskExecutionId(),
                          Arrays.stream(taskExecutionRequested.variables().stream()
                                  .filter(variable -> "routeIds".equals(variable.name()))
                                  .findAny().orElseThrow().value().split(",")).toList(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "releaseId".equals(variable.name()))
                                  .findAny().orElseThrow().value(),
                          taskExecutionRequested.variables().stream()
                                  .filter(variable -> "deploymentId".equals(variable.name()))
                                  .findAny().orElseThrow().value()))).start();
              }

          }


        };
    }

}
