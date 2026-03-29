package io.mateu.workflow.controlplaneservice.application.usecases.site.scrap;

import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.application.query.PageQueryService;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RoutePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ScrapUseCase {

    final SiteRepository siteRepository;
    final PageRepository pageRepository;
    final LanguageRepository languageRepository;
    final CountryRepository countryRepository;
    final RouteRepository routeRepository;

    public void handle(ScrapCommand command) {
        log.info("Scraping site with id {}", command.siteId());
        var site = siteRepository.findById(new SiteId(command.siteId())).orElseThrow(() -> new IllegalArgumentException("Site not found"));
        var pages = pageRepository.findBySiteId(new SiteId(command.siteId()));
        var languages = languageRepository.findAll();
        var countries = countryRepository.findAll();
        languages.forEach(language -> countries.forEach(country -> pages
                .forEach(page -> {
            var routeUrl = site.getUrl().url() + "/" + language.getCode().code() + page.getPath().path();
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
        })));
    }

}
