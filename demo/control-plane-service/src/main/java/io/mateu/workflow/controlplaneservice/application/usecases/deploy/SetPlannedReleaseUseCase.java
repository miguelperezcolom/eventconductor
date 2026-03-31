package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.usecases.ProgressReporter;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import io.mateu.workflow.controlplaneservice.infra.out.github.GitHubReleaseSettingPublisherService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class SetPlannedReleaseUseCase {

    final RouteRepository routeRepository;
    @SneakyThrows
    public void handle(DeployCommand command) {
        log.info("setting planned release {} for routes {}", command.releaseId(), command.routeIds());
        routeRepository.findAll().stream()
                .filter(route -> command.routeIds() == null
                        || command.routeIds().isEmpty()
                        || command.routeIds().contains("" + route.getId().id()))
                .forEach(route -> {
                    route.updatePlannedRelease(new ReleaseId(Long.valueOf(command.releaseId())));
                    routeRepository.save(route);
                });

    }

}
