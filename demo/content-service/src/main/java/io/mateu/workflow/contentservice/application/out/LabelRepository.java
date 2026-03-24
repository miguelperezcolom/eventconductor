package io.mateu.workflow.contentservice.application.out;

import io.mateu.workflow.contentservice.domain.aggregates.label.Label;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;

public interface LabelRepository extends Repository<Label, LabelId> {
}
