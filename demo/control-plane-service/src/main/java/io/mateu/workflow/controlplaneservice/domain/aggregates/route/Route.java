package io.mateu.workflow.controlplaneservice.domain.aggregates.route;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
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


    public static Route of(RouteName name, LanguageCode language, CountryCode country, PageId page, RoutePath path, RouteUrl url) {
        Route p = new Route();
        p.name = name;
        p.language = language;
        p.country = country;
        p.page = page;
        p.path = path;
        p.url = url;
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

}
