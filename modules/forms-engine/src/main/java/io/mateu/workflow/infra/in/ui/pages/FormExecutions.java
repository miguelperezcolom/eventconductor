package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@Slf4j
public class FormExecutions extends AutoCrud<FormExecution> {

    final FormExecutionRepository repository;

    @Override
    public CrudRepository<FormExecution> store() {
        return repository;
    }

    @ListToolbarButton(rowsSelectedRequired = true)
    public void claim(List<FormExecution> selectedRows) {
      log.info("claiming " + selectedRows.size() + " rows");
    }
}
