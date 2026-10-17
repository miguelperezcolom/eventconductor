package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.AnalyticsProjectionStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

interface AnalyticsProjectionStateRepository
        extends JpaRepository<AnalyticsProjectionStateEntity, Integer> {
}
