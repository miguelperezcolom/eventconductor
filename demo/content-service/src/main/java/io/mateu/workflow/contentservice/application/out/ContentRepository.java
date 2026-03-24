package io.mateu.workflow.contentservice.application.out;

import io.mateu.workflow.contentservice.domain.aggregates.content.Content;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;

public interface ContentRepository extends Repository<Content, ContentId> {
}
