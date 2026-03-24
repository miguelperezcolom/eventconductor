package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.AssetVersion;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;

public interface AssetVersionRepository extends Repository<AssetVersion, AssetVersionId> {
}
