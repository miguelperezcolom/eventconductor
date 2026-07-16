package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.AutoNamedView;
import io.mateu.core.infra.declarative.orchestrators.crud.FilteredAutoCrud;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends FilteredAutoCrud<ProcessFilters, ProcessRow> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final RetryProcessUseCase retryProcessUseCase;

    @Override
    public Class filtersClass() {
        return ProcessFilters.class;
    }

    @Override
    public CrudRepository<ProcessRow> repository() {
        return processCrudAdapter.repository();
    }

    @Override
    public CrudAdapter<AutoNamedView<ProcessRow>, AutoNamedView<ProcessRow>, ProcessFilters, ProcessRow, String> adapter() {
        var parent = super.adapter();
        return new CrudAdapter<>() {
            @Override
            public ListingData<ProcessRow> search(String searchText, ProcessFilters filters, Pageable pageable, HttpRequest httpRequest) {
                return processCrudAdapter.search(searchText, filters, pageable, httpRequest);
            }
            @Override
            public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
                parent.deleteAllById(selectedIds, httpRequest);
            }
            @Override
            public Object getView(String id, HttpRequest httpRequest) {
                return processCrudAdapter.getView(id, httpRequest);
            }
            @Override
            public Object getEditor(String id, HttpRequest httpRequest) {
                return processCrudAdapter.getView(id, httpRequest);
            }
            @Override
            public Object getCreationForm(HttpRequest httpRequest) {
                return processCrudAdapter.getCreationForm(httpRequest);
            }
        };
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String saveLabel() {
        return "Create";
    }

    @ListToolbarButton(rowsSelectedRequired = true)
    public void retry(List<ProcessRow> selectedRows) {
        selectedRows.forEach(row -> {
            retryProcessUseCase.handle(new RetryProcessCommand(row.id()));
        });
    }

}
