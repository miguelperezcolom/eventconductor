package io.mateu.workflow.controlplaneservice.domain.aggregates.page;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PagePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Page extends AggregateRoot {

PageId id;

SiteId siteId;

PageName name;

PagePath path;


public static Page of(SiteId siteId, PageName name, PagePath path) {
Page p = new Page();
p.siteId = siteId;
p.name = name;
p.path = path;
return p;
}

public void update(SiteId siteId, PageName name, PagePath path) {
    this.siteId = siteId;
this.name = name;
this.path = path;
}

            }
