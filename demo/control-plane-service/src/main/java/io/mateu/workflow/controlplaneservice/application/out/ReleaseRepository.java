package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;

import java.util.List;

public interface ReleaseRepository extends Repository<Release, ReleaseId> {
    List<Release> findAll();
}
