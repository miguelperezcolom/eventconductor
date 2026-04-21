package io.mateu.workflow.controlplaneservice.domain.aggregates.page;


import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.*;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

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

    PageChangeFrequency changeFrequency;

    PagePriority priority;

    PageLastModification lastModification;

    List<PageCheck> checks;


    public static Page of(SiteId siteId,
                          PageName name,
                          PagePath path,
                          PageJsonLd jsonLd,
                          PageDependsOnLanguage dependsOnLanguage,
                          PageDependsOnCountry dependsOnCountry,
                          PageChangeFrequency changeFrequency,
                          PagePriority priority,
                          PageLastModification lastModification,
                          List<PageCheck> checks) {
        Page p = new Page();
        p.siteId = siteId;
        p.name = name;
        p.path = path;
        p.jsonLd = jsonLd;
        p.dependsOnLanguage = dependsOnLanguage;
        p.dependsOnCountry = dependsOnCountry;
        p.changeFrequency = changeFrequency;
        p.priority = priority;
        p.lastModification = lastModification;
        p.checks = checks;

        return p;
    }

    public void update(SiteId siteId,
                       PageName name,
                       PagePath path,
                       PageJsonLd jsonLd,
                       PageDependsOnLanguage dependsOnLanguage,
                       PageDependsOnCountry dependsOnCountry,
                       PageChangeFrequency changeFrequency,
                       PagePriority priority,
                       List<PageCheck> checks) {
        this.siteId = siteId;
        this.name = name;
        this.path = path;
        this.jsonLd = jsonLd;
        this.dependsOnLanguage = dependsOnLanguage;
        this.dependsOnCountry = dependsOnCountry;
        this.changeFrequency = changeFrequency;
        this.priority = priority;
        this.checks = checks;
    }

}
