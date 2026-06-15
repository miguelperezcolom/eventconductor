package io.mateu.workflow.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.uidl.interfaces.LookupLabelSupplier;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FormIdLabelSupplier implements LookupLabelSupplier {

    final FormRepository formRepository;

    @Override
    public String label(String fieldId, Object id, HttpRequest httpRequest) {
        return formRepository.findById((String) id)
                .map(Form::name)
                .orElse("No form with id " + id);
    }
}
