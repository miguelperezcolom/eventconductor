package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.workflow.controlplaneservice.application.query.dto.CountryDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierRow;

public interface TierQueryService extends QueryService<TierDto, TierRow, Long> {
}
