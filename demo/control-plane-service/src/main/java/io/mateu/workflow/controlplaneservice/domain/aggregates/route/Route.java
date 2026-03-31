package io.mateu.workflow.controlplaneservice.domain.aggregates.route;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteHash;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RoutePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Route extends AggregateRoot {

    RouteId id;

    RouteName name;

    LanguageCode language;

    CountryCode country;

    PageId page;

    RoutePath path;

    RouteUrl url;

    RouteHash hash;

    RouteHash deployedHash;

    ReleaseId release;

    ReleaseId plannedRelease;


    public static Route of(RouteName name, LanguageCode language, CountryCode country, PageId page, RoutePath path, RouteUrl url) {
        Route p = new Route();
        p.name = name;
        p.language = language;
        p.country = country;
        p.page = page;
        p.path = path;
        p.url = url;
        p.hash = new RouteHash("");
        p.deployedHash = new RouteHash("");
        return p;
    }

    public void update(RouteName name, LanguageCode language, CountryCode country, PageId page, RoutePath path, RouteUrl url) {
        this.name = name;
        this.language = language;
        this.country = country;
        this.page = page;
        this.path = path;
        this.url = url;
    }

    public void updateHash(RouteHash routeHash) {
        this.hash = routeHash;
    }

    public void updateDeployedHash(RouteHash routeHash) {
        this.deployedHash = routeHash;
    }

    public void updateRelease(ReleaseId release) {
        this.release = release;
    }

    public void updatePlannedRelease(ReleaseId release) {
        this.plannedRelease = release;
    }
}
