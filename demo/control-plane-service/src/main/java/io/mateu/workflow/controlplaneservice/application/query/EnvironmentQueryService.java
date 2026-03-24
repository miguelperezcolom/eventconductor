package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentRow;

public interface EnvironmentQueryService extends QueryService<EnvironmentDto, EnvironmentRow, Long> {
}
