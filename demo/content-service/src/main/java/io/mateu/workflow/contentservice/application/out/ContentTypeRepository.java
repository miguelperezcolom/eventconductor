package io.mateu.workflow.contentservice.application.out;

import io.mateu.workflow.contentservice.domain.aggregates.contenttype.ContentType;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;

public interface ContentTypeRepository extends Repository<ContentType, ContentTypeId> {
}
