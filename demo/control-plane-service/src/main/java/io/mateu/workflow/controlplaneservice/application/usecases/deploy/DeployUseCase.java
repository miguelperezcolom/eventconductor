package io.mateu.workflow.controlplaneservice.application.usecases.deploy;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import io.mateu.workflow.controlplaneservice.infra.out.github.GitHubPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DeployUseCase {

    final ReleaseRepository releaseRepository;
    final RouteRepository routeRepository;
    final GitHubPublisherService publisher;

    final List<Step> steps = new ArrayList<>();
    final List<Message> messages = new ArrayList<>();
    final List<Error> errors = new ArrayList<>();
    final List<Resource> resources = new ArrayList<>();

    @SneakyThrows
    public void handle(DeployCommand command) {
        log.info("deploying release {} for routes {}", command.releaseId(), command.routeIds());
        steps.clear();
        messages.clear();
        errors.clear();
        resources.clear();

        steps.add(new Step("x", "1", "Create content", new Status(StatusType.INFO, "Pending")));
        steps.add(new Step("x", "2", "Push", new Status(StatusType.INFO, "Pending")));
        steps.add(new Step("x", "3", "Verify deployment", new Status(StatusType.INFO, "Pending")));
        steps.add(new Step("x", "4", "Update releases", new Status(StatusType.INFO, "Pending")));

        var releaseId = "" + command.releaseId();
        if ("Blue".equals(command.releaseId())) {
            releaseId = "" + releaseRepository.findAll().stream()
                    .filter(r -> r.getStatus().equals(ReleaseStatus.Blue))
                    .findFirst().orElseThrow()
                    .getId().id();
        }
        if ("Green".equals(command.releaseId())) {
            releaseId = "" + releaseRepository.findAll().stream()
                    .filter(r -> r.getStatus().equals(ReleaseStatus.Green))
                    .findFirst().orElseThrow()
                    .getId().id();
        }

        publisher.publishAndVerify(releaseId, null, this);

        String finalReleaseId = releaseId;
        routeRepository.findAll().forEach(route -> {
            route.updateRelease(new ReleaseId(Long.valueOf(finalReleaseId)));
            routeRepository.save(route);
        });

        steps.set(3, new Step("x", "4", "Update releases", new Status(StatusType.SUCCESS, "Complete")));

    }

    public List<Step> getSteps() {
        return steps;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public List<Error> getErrors() {
        return errors;
    }

    public List<Resource> getResources() {
        return resources;
    }
}
