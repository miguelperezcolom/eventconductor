package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceRow;

public interface ResourceQueryService extends QueryService<ResourceDto, ResourceRow, Long> {
}
