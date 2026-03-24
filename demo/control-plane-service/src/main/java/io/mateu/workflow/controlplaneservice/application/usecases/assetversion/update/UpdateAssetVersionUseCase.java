package io.mateu.workflow.controlplaneservice.application.usecases.assetversion.update;

import io.mateu.workflow.controlplaneservice.application.out.AssetVersionRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateAssetVersionUseCase {

final AssetVersionRepository repository;

@Transactional
public void handle(UpdateAssetVersionCommand command) {
var assetversion = repository.findById(new AssetVersionId(Long.valueOf(command.id()))).orElseThrow();
assetversion.update(new AssetVersionName(command.name()));
repository.save(assetversion);
}

}
