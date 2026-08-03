package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.infra.out.github.GitHubReleaseSettingPublisherService;
import io.mateu.workflow.controlplaneservice.infra.out.github.KVReleaseSettingPublisherService;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.WorkerReply;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DeployUseCase {

    final RouteRepository routeRepository;
    final KVReleaseSettingPublisherService publisher;
    final StreamBridge streamBridge;

    @SneakyThrows
    public void handle(DeployCommand command) {
        log.info("deploying release {} for routes {}", command.releaseId(), command.routeIds());


        WorkerReply.send(streamBridge, new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.RUNNING,
                List.of(new Variable("deploymentId", command.deploymentId())),
                command.processId()));

        publisher.setReleaseAndPublishToGitHub(command.taskExecutionId(), command.deploymentId(), command.releaseId());


        WorkerReply.send(streamBridge, new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.COMPLETED,
                List.of(),
                command.processId()));

    }

}
