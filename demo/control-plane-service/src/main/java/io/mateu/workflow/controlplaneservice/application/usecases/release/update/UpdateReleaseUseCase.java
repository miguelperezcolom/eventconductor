package io.mateu.workflow.controlplaneservice.application.usecases.release.update;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateReleaseUseCase {

final ReleaseRepository repository;

@Transactional
public void handle(UpdateReleaseCommand command) {
var release = repository.findById(new ReleaseId(Long.valueOf(command.id()))).orElseThrow();
release.update(new ReleaseName(command.name()));
repository.save(release);
}

}
