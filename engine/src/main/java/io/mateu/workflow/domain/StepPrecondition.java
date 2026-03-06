package io.mateu.workflow.domain;

import io.mateu.uidl.annotations.Colspan;

public record StepPrecondition(String stepId, @Colspan(3) String expression) {
}
