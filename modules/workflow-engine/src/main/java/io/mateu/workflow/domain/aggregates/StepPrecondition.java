package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Lookup;

public record StepPrecondition(
        @Lookup(search = StepIdOptionsSupplier.class, label = StepLabelSupplier.class)
        String stepId,
        @Colspan(3) String expression) {
}
