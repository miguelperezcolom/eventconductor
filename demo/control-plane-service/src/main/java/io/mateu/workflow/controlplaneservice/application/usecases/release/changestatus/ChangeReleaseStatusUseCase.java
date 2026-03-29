package io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ChangeReleaseStatusUseCase {

    final ReleaseRepository repository;

    public void handle(ChangeReleaseStatusCommand command) {
        var status = ReleaseStatus.valueOf(command.status());
        repository.findAll().stream().filter(release -> release.getStatus().equals(status)).forEach(release -> {
            release.updateStatus(ReleaseStatus.Archived);
            repository.save(release);
        });
        command.ids().forEach(id -> {
            var release = repository.findById(new ReleaseId(Long.valueOf(id))).orElseThrow();
            release.updateStatus(status);
            repository.save(release);
        });
    }

}
