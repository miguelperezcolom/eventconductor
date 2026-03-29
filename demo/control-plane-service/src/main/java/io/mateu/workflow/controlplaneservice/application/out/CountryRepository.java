package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;

import java.util.List;

public interface CountryRepository extends Repository<Country, CountryCode> {
    List<Country> findAll();
}
