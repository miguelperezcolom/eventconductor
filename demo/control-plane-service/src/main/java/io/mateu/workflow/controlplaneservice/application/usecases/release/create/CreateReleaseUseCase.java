package io.mateu.workflow.controlplaneservice.application.usecases.release.create;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateReleaseUseCase {

final ReleaseRepository repository;

@Transactional
public String handle(CreateReleaseCommand command) {
return repository.save(Release.of(new ReleaseName(command.name()))
).id().toString();
}

}
