package io.mateu.workflow.controlplaneservice.domain.aggregates.release;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Release extends AggregateRoot {

    ReleaseId id;

    ReleaseName name;

    UserId user;

    ReleaseDate date;

    EnvironmentId environment;

    SiteId site;

    List<PageId> pages;

    List<CountryCode> countries;

    List<LanguageCode> languages;


    public static Release of(ReleaseName name, UserId user, ReleaseDate date, EnvironmentId environment, SiteId site, List<PageId> pages, List<CountryCode> countries, List<LanguageCode> languages) {
        Release p = new Release();
        p.name = name;
        p.user = user;
        p.date = date;
        p.site = site;
        p.pages = pages;
        p.countries = countries;
        p.languages = languages;
        p.environment = environment;
        return p;
    }

    public void update(ReleaseName name, UserId user, ReleaseDate date, EnvironmentId environment, SiteId site, List<PageId> pages, List<CountryCode> countries, List<LanguageCode> languages) {
        this.name = name;
        this.user = user;
        this.date = date;
        this.site = site;
        this.pages = pages;
        this.countries = countries;
        this.languages = languages;
        this.environment = environment;
    }

}
