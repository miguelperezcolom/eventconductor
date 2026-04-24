package io.mateu.workflow.controlplaneservice.application.usecases.site.scrape;

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
@RequiredArgsConstructor
@Slf4j
public class AskForScrapeUseCase {

    final StreamBridge streamBridge;

    public void handle(AskForScrapeCommand command) {
        log.info("Scraping site with code {}", command.siteId());
        streamBridge.send("upstream", new ProcessCreationRequested(
                "52ea7ab0-be39-44e4-af06-88dd61f2b0cd",
                command.processBusinessKey(),
                List.of(new Variable("siteId", command.siteId()))));
    }

}
