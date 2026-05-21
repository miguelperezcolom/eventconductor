package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.dtos.UIFragmentDto;
import io.mateu.dtos.UIIncrementDto;
import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.data.*;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import io.mateu.workflow.domain.aggregates.Process;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends CrudOrchestrator<Object, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final CreateProcessForm createProcessForm;
    final ProcessRepository processRepository;
    final RetryProcessUseCase retryProcessUseCase;


    @Override
    public CrudAdapter<Object, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> adapter() {
        return processCrudAdapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }

    @ListToolbarButton(rowsSelectedRequired = false)
    public CreateProcessForm create() {
        return createProcessForm;
    }

    @ListToolbarButton(rowsSelectedRequired = true)
    public void retry(List<ProcessRow> selectedRows) {
        selectedRows.forEach(row -> {
            retryProcessUseCase.handle(new RetryProcessCommand(row.id()));
        });
    }

    @ViewToolbarButton
    public void retry(SimpleProcessViewModel state) {
        retryProcessUseCase.handle(new RetryProcessCommand(state.getId()));
    }

}
