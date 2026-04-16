package io.mateu.workflow.controlplaneservice.domain.aggregates.page;


import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Page extends AggregateRoot {

    PageId id;

    SiteId siteId;

    PageName name;

    PagePath path;

    PageJsonLd jsonLd;

    PageDependsOnLanguage dependsOnLanguage;

    PageDependsOnCountry dependsOnCountry;


    public static Page of(SiteId siteId,
                          PageName name,
                          PagePath path,
                          PageJsonLd jsonLd,
                          PageDependsOnLanguage dependsOnLanguage,
                          PageDependsOnCountry dependsOnCountry) {
        Page p = new Page();
        p.siteId = siteId;
        p.name = name;
        p.path = path;
        p.jsonLd = jsonLd;
        p.dependsOnLanguage = dependsOnLanguage;
        p.dependsOnCountry = dependsOnCountry;
        return p;
    }

    public void update(SiteId siteId,
                       PageName name,
                       PagePath path,
                       PageJsonLd jsonLd,
                       PageDependsOnLanguage dependsOnLanguage,
                       PageDependsOnCountry dependsOnCountry) {
        this.siteId = siteId;
        this.name = name;
        this.path = path;
        this.jsonLd = jsonLd;
        this.dependsOnLanguage = dependsOnLanguage;
        this.dependsOnCountry = dependsOnCountry;
    }

}
