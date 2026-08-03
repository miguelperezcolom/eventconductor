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
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.WorkerReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The worker side of the control plane: it picks up the ACTION steps of the release workflow and
 * answers the engine through {@link WorkerReply}.
 *
 * <p>Each task is handled on the consumer thread, deliberately. Handing it to a thread of its own
 * — which this used to do — returns from the listener immediately and commits the offset, so when
 * the reply cannot be published there is nothing left for Kafka to redeliver and the step waits
 * forever. A task that legitimately runs for minutes belongs in a worker with a raised
 * {@code max.poll.interval.ms}, not in a detached thread.
 */
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
    public Consumer<DomainEvent> consumeWorkerEvent() {
        return event -> {
            log.info("Received event: " + event);
            if (!(event instanceof TaskExecutionRequested task)) {
                return;
            }

            switch (task.stepId()) {
                case "capturar" -> scrapeUseCase.handle(new ScrapeCommand(
                        variable(task, "siteId"), task.taskExecutionId(), task.processId()));

                case "download" -> downloadAssetsUseCase.handle(new DownloadAssetsCommand(
                        variable(task, "siteId"), task.taskExecutionId(), task.processId()));

                case "create-content" -> createReleaseUseCase.handle(new CreateReleaseCommand(
                        variable(task, "name"),
                        variable(task, "siteId"),
                        variable(task, "userId"),
                        task.taskExecutionId(),
                        task.processId()));

                case "upload" -> uploadToR2UseCase.handle(new UploadToR2Command(
                        variable(task, "name"),
                        variable(task, "siteId"),
                        variable(task, "userId"),
                        task.taskExecutionId(),
                        variable(task, "releaseId"),
                        task.processId()));

                case "update-script" -> deployUseCase.handle(deployCommand(task, List.of()));

                case "verify" -> verifyDeploymentUseCase.handle(deployCommand(task, List.of()));

                case "update-releases" -> updateRoutesUseCase.handle(deployCommand(task,
                        Arrays.stream(variable(task, "routeIds").split(",")).toList()));

                default -> log.debug("No handler for step {}", task.stepId());
            }
        };
    }

    private DeployCommand deployCommand(TaskExecutionRequested task, List<String> routeIds) {
        return new DeployCommand(
                task.taskExecutionId(),
                routeIds,
                variable(task, "releaseId"),
                variable(task, "deploymentId"),
                task.processId());
    }

    private String variable(TaskExecutionRequested task, String name) {
        return task.variables().stream()
                .filter(variable -> name.equals(variable.name()))
                .findAny()
                .map(Variable::value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Step " + task.stepId() + " needs a '" + name + "' variable"));
    }

}
