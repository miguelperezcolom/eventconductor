package io.mateu.workflow.controlplaneservice.application.usecases.createrelease;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.application.out.CountryRepository;
import io.mateu.workflow.controlplaneservice.application.out.EnvironmentRepository;
import io.mateu.workflow.controlplaneservice.application.out.LanguageRepository;
import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.application.query.ChangeQueryService;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service("createrelease.CreateReleaseUseCase")
@Transactional
@Slf4j
@RequiredArgsConstructor
public class CreateReleaseUseCase {

    final RouteRepository routeRepository;
    final SiteRepository siteRepository;
    final ReleaseRepository releaseRepository;
    final CountryRepository countryRepository;
    final LanguageRepository languageRepository;
    final AssetRepository assetRepository;
    final ResourceRepository resourceRepository;
    final EnvironmentRepository environmentRepository;

    public void handle(CreateReleaseCommand command) {
        log.info("create release {}", command);
        var routes = routeRepository.findAll();

        var site = siteRepository.findById(new SiteId(command.siteId())).orElseThrow();

        var release = Release.of(
                new ReleaseName(command.name()),
                new UserId(command.userId()),
                new ReleaseDate(LocalDateTime.now()),
                new EnvironmentId(command.environmentId()),
                new SiteId(command.siteId()),
                ReleaseStatus.New
        );

        releaseRepository.save(release);

        routes.forEach(route -> {
            route.updateDeployedHash(route.getHash());
            routeRepository.save(route);
        });


    }

}
