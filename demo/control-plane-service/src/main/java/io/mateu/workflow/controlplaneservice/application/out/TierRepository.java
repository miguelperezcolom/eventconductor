package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.Tier;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;

import java.util.List;

public interface TierRepository extends Repository<Tier, TierId> {
    List<Tier> findAll();
}
