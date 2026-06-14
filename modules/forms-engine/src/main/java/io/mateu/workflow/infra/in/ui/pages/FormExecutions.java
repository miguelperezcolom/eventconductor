package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoListAdapter;
import io.mateu.core.infra.declarative.orchestrators.crud.AutoListOrchestrator;
import io.mateu.workflow.infra.in.ui.adapters.FormExecutionCrudAdapter;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Style;
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
public class FormExecutions extends AutoListOrchestrator<FormExecution> {

    final FormExecutionCrudAdapter executionAutoCrudAdapter;

    @Override
    public AutoListAdapter<FormExecution> simpleAdapter() {
        return executionAutoCrudAdapter;
    }

    @ListToolbarButton(rowsSelectedRequired = true)
    public void claim(List<FormExecution> selectedRows) {
      log.info("claiming " + selectedRows.size() + " rows");
    }
}
