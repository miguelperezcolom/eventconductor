package io.mateu.workflow.controlplaneservice.application.usecases.createrelease;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.usecases.ProgressReporter;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import io.mateu.workflow.controlplaneservice.infra.out.github.GitHubReleaseFolderPublisherService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service("createrelease.CreateReleaseUseCase")
@Transactional
@Slf4j
@RequiredArgsConstructor
public class CreateReleaseUseCase implements ProgressReporter {

    final RouteRepository routeRepository;
    final ReleaseRepository releaseRepository;
    final GitHubReleaseFolderPublisherService publisher;

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
    public void handle(CreateReleaseCommand command) {
        log.info("create release {}", command);

        if (status == null || status.type().equals(StatusType.SUCCESS)  || status.type().equals(StatusType.DANGER)  || status.type().equals(StatusType.NONE)) {

            status = new Status(StatusType.WARNING, "Running");

            reset();

            steps.add(new Step("x", "1", "Create content", new Status(StatusType.INFO, "Pending")));
            steps.add(new Step("x", "2", "Push", new Status(StatusType.INFO, "Pending")));

            try {

                status = new Status(StatusType.WARNING, "Running");

                var routes = routeRepository.findAll();

                var releaseId = releaseRepository.save(Release.of(
                        new ReleaseName(command.name()),
                        new UserId(command.userId()),
                        new ReleaseDate(LocalDateTime.now()),
                        new SiteId(command.siteId()),
                        ReleaseStatus.New
                ));

                publisher.publishReleaseFolderAndVerify("" + releaseId.id(), this);

                routes.forEach(route -> {
                    route.updateDeployedHash(route.getHash());
                    routeRepository.save(route);
                });

                releaseRepository.findAll().stream().filter(release -> release.getStatus().equals(ReleaseStatus.Green)).forEach(release -> {
                    release.updateStatus(ReleaseStatus.Archived);
                    releaseRepository.save(release);
                });
                var release = releaseRepository.findById(releaseId).orElseThrow();
                release.updateStatus(ReleaseStatus.Green);
                releaseRepository.save(release);

                status = new Status(StatusType.SUCCESS, "Complete");
            } catch (Throwable e) {
                log.error("error", e);
                failed();
                status = new Status(StatusType.DANGER, "Error");
            }

        }

    }

}
