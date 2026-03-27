package io.mateu.workflow.controlplaneservice.domain.aggregates.page;


import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageJsonLd;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PagePath;
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


    public static Page of(SiteId siteId, PageName name, PagePath path, PageJsonLd jsonLd) {
        Page p = new Page();
        p.siteId = siteId;
        p.name = name;
        p.path = path;
        p.jsonLd = jsonLd;
        return p;
    }

    public void update(SiteId siteId, PageName name, PagePath path, PageJsonLd jsonLd) {
        this.siteId = siteId;
        this.name = name;
        this.path = path;
        this.jsonLd = jsonLd;
    }

}
