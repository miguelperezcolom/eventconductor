package io.mateu.workflow.controlplaneservice.domain.aggregates.release;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
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

    ReleaseStatus status;


    public static Release of(ReleaseName name, UserId user, ReleaseDate date, EnvironmentId environment, SiteId site, ReleaseStatus status) {
        Release p = new Release();
        p.name = name;
        p.user = user;
        p.date = date;
        p.site = site;
        p.environment = environment;
        p.status = status;
        return p;
    }

    public void update(ReleaseName name, UserId user, ReleaseDate date, EnvironmentId environment, SiteId site) {
        this.name = name;
        this.user = user;
        this.date = date;
        this.site = site;
        this.environment = environment;
    }

    public void updateStatus(ReleaseStatus status) {
        this.status = status;
    }
}
