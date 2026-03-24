package io.mateu.workflow.controlplaneservice.application.usecases.asset.update;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateAssetUseCase {

final AssetRepository repository;

@Transactional
public void handle(UpdateAssetCommand command) {
var asset = repository.findById(new AssetId(Long.valueOf(command.id()))).orElseThrow();
asset.update(new AssetName(command.name()));
repository.save(asset);
}

}
