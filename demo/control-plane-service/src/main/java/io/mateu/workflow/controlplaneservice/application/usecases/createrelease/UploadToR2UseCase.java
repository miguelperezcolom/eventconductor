package io.mateu.workflow.controlplaneservice.application.usecases.createrelease;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.infra.out.r2.R2ReleaseFolderPublisherService;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class UploadToR2UseCase {

    final RouteRepository routeRepository;
    final ReleaseRepository releaseRepository;
    final R2ReleaseFolderPublisherService publisher;
    final StreamBridge streamBridge;

    @SneakyThrows
    public void handle(UploadToR2Command command) {
        log.info("upload to r2 {}", command);

        var routes = routeRepository.findAll();

        var releaseId = command.releaseId();

        publisher.publishReleaseFolderAndVerify(releaseId, command.taskExecutionId());

        routes.forEach(route -> {
            route.updateDeployedHash(route.getHash());
            routeRepository.save(route);
        });

        releaseRepository.findAll().stream().filter(release -> release.getStatus().equals(ReleaseStatus.Green)).forEach(release -> {
            release.updateStatus(ReleaseStatus.Archived);
            releaseRepository.save(release);
        });
        var release = releaseRepository.findById(new ReleaseId(Long.valueOf(releaseId))).orElseThrow();
        release.updateStatus(ReleaseStatus.Green);
        releaseRepository.save(release);

        streamBridge.send("upstream", new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.COMPLETED,
                List.of()));
    }

}
