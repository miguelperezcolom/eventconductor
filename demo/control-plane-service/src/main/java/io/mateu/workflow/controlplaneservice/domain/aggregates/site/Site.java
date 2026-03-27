package io.mateu.workflow.controlplaneservice.domain.aggregates.site;


import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteUrl;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Site extends AggregateRoot {

    SiteId id;

    SiteName name;

    SiteUrl url;


    public static Site of(SiteId id, SiteName name, SiteUrl url) {
        Site p = new Site();
        p.id = id;
        p.name = name;
        p.url = url;
        return p;
    }

    public void update(SiteName name, SiteUrl url) {

        this.name = name;
        this.url = url;
    }

}
