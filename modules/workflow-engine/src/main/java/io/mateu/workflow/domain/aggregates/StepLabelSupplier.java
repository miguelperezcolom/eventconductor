package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;

public class StepLabelSupplier implements LabelSupplier {
    @Override
    public String label(String fieldName, Object id, HttpRequest httpRequest) {
        return "xxx";
    }
}
