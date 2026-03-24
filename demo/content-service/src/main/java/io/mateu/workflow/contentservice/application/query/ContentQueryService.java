package io.mateu.workflow.contentservice.application.query;

import io.mateu.workflow.contentservice.application.query.dto.ContentDto;
import io.mateu.workflow.contentservice.application.query.dto.ContentRow;

public interface ContentQueryService extends QueryService<ContentDto, ContentRow, Long> {
}
