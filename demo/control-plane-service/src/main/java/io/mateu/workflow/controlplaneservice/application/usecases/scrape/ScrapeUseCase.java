package io.mateu.workflow.controlplaneservice.application.usecases.scrape;

import io.mateu.workflow.controlplaneservice.application.out.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RoutePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ScrapeUseCase {

    final SiteRepository siteRepository;
    final PageRepository pageRepository;
    final LanguageRepository languageRepository;
    final CountryRepository countryRepository;
    final RouteRepository routeRepository;
    final StreamBridge streamBridge;

    public void handle(ScrapeCommand command) {
        log.info("Scraping site with id {}", command.siteId());

        streamBridge.send("upstream", new TaskStatusChanged(command.taskExecutionId(), TaskStatus.RUNNING));

        var site = siteRepository.findById(new SiteId(command.siteId())).orElseThrow(() -> new IllegalArgumentException("Site not found"));
        var pages = pageRepository.findBySiteId(new SiteId(command.siteId()));
        var languages = languageRepository.findAll();
        var countries = countryRepository.findAll();
        languages.forEach(language -> countries.forEach(country -> pages
                .forEach(page -> {
                    var routeUrl = site.getUrl().url() + "/" + language.getCode().code() + page.getPath().path();

                    streamBridge.send("upstream", new TaskLogEmitted(command.taskExecutionId(), MessageType.Info, "Scraping " + routeUrl + "..."));


                    var found = routeRepository.findAll().stream()
                            .filter(r -> r.getCountry().code().equals(country.getCode().code()))
                            .filter(r -> routeUrl.equals(r.getUrl().url())).findAny();
                    if (found.isEmpty()) {
                        routeRepository.save(Route.of(
                                new RouteName(site.getId().id() + "/" + language.getCode().code() + page.getPath().path() + "_" + country.getCode().code()),
                                language.getCode(),
                                country.getCode(),
                                page.getId(),
                                new RoutePath("/" + language.getCode().code() + page.getPath().path()),
                                new RouteUrl(routeUrl)));
                    }

                    streamBridge.send("upstream", new TaskLogEmitted(command.taskExecutionId(), MessageType.Info, "Scrapped " + routeUrl));

                })));

        streamBridge.send("upstream", new TaskStatusChanged(command.taskExecutionId(), TaskStatus.COMPLETED));
    }

}
