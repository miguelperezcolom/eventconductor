package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.workflow.controlplaneservice.application.query.dto.PageDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageRow;

public interface PageQueryService extends QueryService<PageDto, PageRow, Long> {
}
