package io.mateu.workflow.controlplaneservice.application.usecases.scrape;

import io.mateu.workflow.controlplaneservice.application.out.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.Language;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RoutePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.Site;
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

import java.util.List;

@Service
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
        log.info("Scraping site with code {}", command.siteId());

        streamBridge.send("upstream", new TaskStatusChanged(command.taskExecutionId(), TaskStatus.RUNNING, List.of()));

        var site = siteRepository.findById(new SiteId(command.siteId())).orElseThrow(() -> new IllegalArgumentException("Site not found"));
        var pages = pageRepository.findBySiteId(new SiteId(command.siteId()));
        // páginas que no dependen de idioma ni país
        pages.stream().filter(page -> !page.getDependsOnLanguage().depends()).filter(page -> !page.getDependsOnCountry().depends()).forEach(page -> {
                    var path = page.getPath().path();
                    var routeUrl = site.getUrl().url() + path;
                    saveRoute(command, null, null, page, routeUrl, site, path);
                });

        var countries = countryRepository.findAll();
        // páginas que no dependen de idioma pero si de país
        countries.forEach(country ->
                pages.stream().filter(page -> !page.getDependsOnLanguage().depends()).forEach(page -> {
                    var path = page.getPath().path();
                    var routeUrl = site.getUrl().url() + path;
                    saveRoute(command, null, country.getCode(), page, routeUrl, site, path);
        }));
        var languages = languageRepository.findAll();
        // páginas que dependen de idioma pero no de país
        languages.forEach(language -> pages
                .stream().filter(page -> page.getDependsOnLanguage().depends())
                .forEach(page -> {
                    var path = page.getPath().path();
                    var routeUrl = site.getUrl().url() + "/" + language.getCode().code() + path;
                    var languageCode = language.getCode();
                    saveRoute(command, languageCode, null, page, routeUrl, site, "/" + languageCode.code() + path);
                }));

        // páginas que no dependen de idioma pero si de país
        languages.forEach(language -> countries.forEach(country -> pages
                .stream().filter(page -> page.getDependsOnLanguage().depends())
                .forEach(page -> {
                    var path = page.getPath().path();
                    var routeUrl = site.getUrl().url() + "/" + language.getCode().code() + path;
                    var languageCode = language.getCode();
                    saveRoute(command, languageCode, country.getCode(), page, routeUrl, site, "/" + languageCode.code() + path);
                })));

        streamBridge.send("upstream", new TaskStatusChanged(command.taskExecutionId(), TaskStatus.COMPLETED, List.of()));
    }

    private void saveRoute(ScrapeCommand command, LanguageCode languageCode, CountryCode countryCode, Page page, String routeUrl, Site site, String path) {
        var found = routeRepository.findAll().stream()
                .filter(r -> (countryCode == null && (r.getCountry() == null || r.getCountry().code() == null))
                        || (countryCode != null
                        && r.getCountry() != null
                        && r.getCountry().code() != null
                        && r.getCountry().code().equals(countryCode.code())))
                .filter(r -> routeUrl.equals(r.getUrl().url())).findAny();
        if (found.isEmpty()) {
            log.info("Saving new route {} for site {} with country {} and language {}", routeUrl, site.getId().id(), countryCode, languageCode);
            routeRepository.save(Route.of(
                    new RouteName(site.getId().id() + path + (countryCode != null?("_" + countryCode.code()):"")),
                    languageCode,
                    countryCode,
                    page.getId(),
                    new RoutePath(path),
                    new RouteUrl(routeUrl)));
        }

        streamBridge.send("upstream", new TaskLogEmitted(command.taskExecutionId(), MessageType.Info, "Saved " + routeUrl));
    }

}
