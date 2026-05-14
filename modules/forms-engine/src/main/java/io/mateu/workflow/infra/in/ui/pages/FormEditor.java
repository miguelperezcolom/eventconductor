package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.FormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class FormEditor {

    final FormRepository repository;

    String formId;

    io.mateu.uidl.data.FormEditor form;


    public FormEditor load(String workflowId) {
        this.formId = workflowId;
        var def = repository.findById(workflowId).orElseThrow();
        form = new io.mateu.uidl.data.FormEditor(toJson(def), "", "");
        return this;
    }

}
