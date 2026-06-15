package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.uidl.interfaces.LookupLabelSupplier;

public class StepLabelSupplier implements LookupLabelSupplier {
    @Override
    public String label(String fieldName, Object id, HttpRequest httpRequest) {
        return "xxx";
    }
}
