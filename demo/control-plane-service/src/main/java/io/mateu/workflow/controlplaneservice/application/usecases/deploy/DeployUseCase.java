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
public class DeployUseCase implements ProgressReporter {

    final RouteRepository routeRepository;
    final GitHubReleaseSettingPublisherService publisher;

    Status status;
    @Getter
    final List<Step> steps = new ArrayList<>();
    @Getter
    final List<Message> messages = new ArrayList<>();
    @Getter
    final List<Error> errors = new ArrayList<>();
    @Getter
    final List<Resource> resources = new ArrayList<>();

    @SneakyThrows
    public void handle(DeployCommand command) {
        log.info("deploying release {} for routes {}", command.releaseId(), command.routeIds());
        if (status == null || status.type().equals(StatusType.SUCCESS)  || status.type().equals(StatusType.DANGER)  || status.type().equals(StatusType.NONE)) {
            reset();

            status = new Status(StatusType.WARNING, "Running");

            steps.add(new Step("x", "1", "Create content", new Status(StatusType.INFO, "Pending")));
            steps.add(new Step("x", "2", "Push", new Status(StatusType.INFO, "Pending")));
            steps.add(new Step("x", "3", "Verify deployment", new Status(StatusType.INFO, "Pending")));
            steps.add(new Step("x", "4", "Update releases", new Status(StatusType.INFO, "Pending")));

            try {
                var deploymentId = UUID.randomUUID().toString();
                publisher.publishReleaseVersionAndVerify(deploymentId, command.releaseId(), this);

                routeRepository.findAll().stream()
                        .filter(route -> command.routeIds() == null
                                || command.routeIds().isEmpty()
                                || command.routeIds().contains("" + route.getId().id()))
                        .forEach(route -> {
                    route.updateRelease(new ReleaseId(Long.valueOf(command.releaseId())));
                    routeRepository.save(route);
                });

                update(3, StatusType.SUCCESS);

                status = new Status(StatusType.SUCCESS, "Complete");
            } catch (Throwable e) {
                failed(e);
                status = new Status(StatusType.DANGER, "Error");
            }

        }

    }

}
