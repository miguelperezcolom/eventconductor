package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.Asset;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;

import java.util.Optional;

public interface AssetRepository extends Repository<Asset, AssetId> {
    Optional<Asset> findByUrlAndCountry(RouteUrl url, CountryCode country);
}
