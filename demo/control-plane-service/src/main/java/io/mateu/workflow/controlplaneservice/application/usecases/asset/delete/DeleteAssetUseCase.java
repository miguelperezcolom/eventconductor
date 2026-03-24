package io.mateu.workflow.controlplaneservice.application.usecases.asset.delete;

import io.mateu.workflow.controlplaneservice.application.out.AssetRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteAssetUseCase {

final AssetRepository repository;

@Transactional
public void handle(DeleteAssetCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(AssetId::new)
.toList());
}

}
