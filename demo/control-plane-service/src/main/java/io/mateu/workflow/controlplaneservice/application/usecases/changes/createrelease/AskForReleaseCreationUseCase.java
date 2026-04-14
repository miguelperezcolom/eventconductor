package io.mateu.workflow.controlplaneservice.application.usecases.changes.createrelease;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
