package io.mateu.workflow.controlplaneservice.application.usecases.release.delete;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteReleaseUseCase {

final ReleaseRepository repository;

@Transactional
public void handle(DeleteReleaseCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(ReleaseId::new)
.toList());
}

}
