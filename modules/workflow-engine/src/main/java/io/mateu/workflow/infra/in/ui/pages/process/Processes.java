package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoListAdapter;
import io.mateu.core.infra.declarative.orchestrators.crud.AutoListOrchestrator;
import io.mateu.core.infra.declarative.orchestrators.crud.CrudOrchestrator;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.data.NoCreationForm;
import io.mateu.uidl.data.NoEditor;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends AutoListOrchestrator<ProcessRow> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final CreateProcessForm createProcessForm;
    final ProcessRepository processRepository;
    final RetryProcessUseCase retryProcessUseCase;

    @Override
    public String toId(String s) {
        return s;
    }

    @Override
    public AutoListAdapter<ProcessRow> simpleAdapter() {
        return processCrudAdapter;
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
