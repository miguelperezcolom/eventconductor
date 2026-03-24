package io.mateu.workflow.controlplaneservice.application.query;

import io.mateu.workflow.controlplaneservice.application.query.dto.CountryDto;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;

public interface CountryQueryService extends QueryService<CountryDto, CountryRow, Long> {
}
