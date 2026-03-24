package io.mateu.workflow.controlplaneservice.application.usecases.assetversion.create;

import io.mateu.workflow.controlplaneservice.application.out.AssetVersionRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.AssetVersion;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateAssetVersionUseCase {

final AssetVersionRepository repository;

@Transactional
public String handle(CreateAssetVersionCommand command) {
return repository.save(AssetVersion.of(new AssetVersionName(command.name()))
).id().toString();
}

}
