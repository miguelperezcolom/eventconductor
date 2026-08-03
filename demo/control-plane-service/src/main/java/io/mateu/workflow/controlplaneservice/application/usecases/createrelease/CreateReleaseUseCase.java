package io.mateu.workflow.controlplaneservice.application.usecases.createrelease;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
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

import java.time.LocalDateTime;
import java.util.List;

@Service("createrelease.CreateReleaseUseCase")
@Transactional
@Slf4j
@RequiredArgsConstructor
public class CreateReleaseUseCase {

    final ReleaseRepository releaseRepository;
    final StreamBridge streamBridge;

    @SneakyThrows
    public void handle(CreateReleaseCommand command) {
        log.info("create release {}", command);

        var releaseId = releaseRepository.save(Release.of(
                new ReleaseName(command.name()),
                new UserId(command.userId()),
                new ReleaseDate(LocalDateTime.now()),
                new SiteId(command.siteId()),
                ReleaseStatus.New
        ));

        WorkerReply.send(streamBridge, new TaskStatusChanged(
                command.taskExecutionId(),
                TaskStatus.COMPLETED,
                List.of(
                        new Variable("releaseId", releaseId.id().toString())
                ),
                command.processId()));
    }

}
