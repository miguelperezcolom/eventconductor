package io.mateu.workflow.controlplaneservice.application.usecases.changes.createrelease;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.usecases.ProgressReporter;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrape.AskForScrapeCommand;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import io.mateu.workflow.controlplaneservice.infra.out.r2.R2ReleaseFolderPublisherService;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class AskForReleaseCreationUseCase {

    final StreamBridge streamBridge;

    public void handle(AskForReleaseCreationCommand command) {
        log.info("Create release with name {}", command.name());
        streamBridge.send("upstream", new ProcessCreationRequested(
                "97caf06c-6716-4dbb-b858-271093694e3c",
                command.businessKey(),
                List.of(
                        new Variable("name", command.name()),
                        new Variable("siteId", command.siteId()),
                        new Variable("userId", command.userId())
                )));
    }


}
