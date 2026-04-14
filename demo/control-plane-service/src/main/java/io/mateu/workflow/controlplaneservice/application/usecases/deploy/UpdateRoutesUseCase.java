package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.infra.out.github.GitHubReleaseSettingPublisherService;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
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
public class UpdateRoutesUseCase {

    final RouteRepository routeRepository;
    final GitHubReleaseSettingPublisherService publisher;
    final StreamBridge streamBridge;

    @SneakyThrows
    public void handle(DeployCommand command) {
        log.info("deploying release {} for routes {}", command.releaseId(), command.routeIds());

        routeRepository.findAll().stream()
                .filter(route -> command.routeIds() == null
                        || command.routeIds().isEmpty()
                        || command.routeIds().contains("" + route.getId().id()))
                .forEach(route -> {
                    route.updateRelease(new ReleaseId(Long.valueOf(command.releaseId())));
                    routeRepository.save(route);
                });

        streamBridge.send("upstream", new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.COMPLETED,
                List.of()));
    }

}
