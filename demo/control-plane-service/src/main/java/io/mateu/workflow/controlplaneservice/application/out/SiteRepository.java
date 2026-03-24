package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.site.Site;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;

public interface SiteRepository extends Repository<Site, SiteId> {
}
