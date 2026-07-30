package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

// Mateu 271 folded the old CrudAdapter/AutoNamedView extension points into the Crud page and
// made Navigable<Detail,Id>.view() return the (generic) Detail type. The process detail is a
// rich SimpleProcessViewModel that differs from the ProcessRow shown in the list, so this page
// extends Crud directly with Object view/editor/creation-form types (as the old adapter did,
// which returned Object) instead of AutoCrud/FilteredAutoCrud, which pin View = Row.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends Crud<Object, Object, Object, ProcessFilters, ProcessRow, String> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final RetryProcessUseCase retryProcessUseCase;

    @Override
    public Class<ProcessFilters> filtersClass() {
        return ProcessFilters.class;
    }

    @Override
    public Class<ProcessRow> rowClass() {
        return ProcessRow.class;
    }

    public CrudStore<ProcessRow> store() {
        return processCrudAdapter.repository();
    }

    @Override
    public ListingData<ProcessRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        return processCrudAdapter.search(searchRequest.searchText(),
                (ProcessFilters) searchRequest.filters(), searchRequest.pageable(), httpRequest);
    }

    @Override
    public Object view(String id, HttpRequest httpRequest) {
        return processCrudAdapter.getView(id, httpRequest);
    }

    @Override
    public Object edit(String id, HttpRequest httpRequest) {
        return processCrudAdapter.getView(id, httpRequest);
    }

    @Override
    public Object creationForm(HttpRequest httpRequest) {
        return processCrudAdapter.getCreationForm(httpRequest);
    }

    @Override
    public String save(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllById(List<String> ids, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canEdit() {
        return false;
    }

    @Override
    public boolean canDelete() {
        return false;
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
