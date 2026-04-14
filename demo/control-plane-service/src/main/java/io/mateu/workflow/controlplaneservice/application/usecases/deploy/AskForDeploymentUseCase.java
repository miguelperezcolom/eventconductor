package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
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
public class AskForDeploymentUseCase {

    final SetPlannedReleaseUseCase setPlannedReleaseUseCase;
    final StreamBridge streamBridge;

    @SneakyThrows
    public void handle(AskForDeploymentCommand command) {
        log.info("deploying release {} for routes {}", command.releaseId(), command.routeIds());

        var deploymentId = UUID.randomUUID().toString();

        setPlannedReleaseUseCase.handle(new DeployCommand("", command.routeIds(), command.releaseId(), deploymentId));

        streamBridge.send("upstream", new ProcessCreationRequested(
                "37632b1d-e294-4174-a5d2-e71f41e70579",
                command.businessKey(),
                List.of(
                        new Variable("releaseId", command.releaseId()),
                        new Variable("routeIds", String.join(",", command.routeIds())),
                        new Variable("deploymentId", deploymentId)
                )));
    }

}
