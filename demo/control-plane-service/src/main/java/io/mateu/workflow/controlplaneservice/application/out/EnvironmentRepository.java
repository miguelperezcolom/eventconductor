package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.Environment;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;

public interface EnvironmentRepository extends Repository<Environment, EnvironmentId> {
}
