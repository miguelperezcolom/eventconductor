package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.workflow.controlplaneservice.application.query.dto.AssetDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetRow;

public interface AssetQueryService extends QueryService<AssetDto, AssetRow, Long> {
}
