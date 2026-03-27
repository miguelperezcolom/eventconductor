package io.mateu.workflow.controlplaneservice.application.usecases.asset.create;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.Asset;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetPath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateAssetUseCase {

final AssetRepository repository;

@Transactional
public String handle(CreateAssetCommand command) {
return repository.save(Asset.of(new AssetName(command.name()), new AssetPath(command.path()), new AssetUrl(command.url()))
).id().toString();
}

}
