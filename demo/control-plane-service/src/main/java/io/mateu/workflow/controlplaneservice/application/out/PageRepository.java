package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;

import java.util.List;

public interface PageRepository extends Repository<Page, PageId> {

    List<Page> findBySiteId(SiteId siteId);

}
