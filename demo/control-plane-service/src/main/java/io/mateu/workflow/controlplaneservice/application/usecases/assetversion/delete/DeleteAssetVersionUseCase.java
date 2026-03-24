package io.mateu.workflow.controlplaneservice.application.usecases.assetversion.delete;

import io.mateu.workflow.controlplaneservice.application.out.AssetVersionRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteAssetVersionUseCase {

final AssetVersionRepository repository;

@Transactional
public void handle(DeleteAssetVersionCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(AssetVersionId::new)
.toList());
}

}
