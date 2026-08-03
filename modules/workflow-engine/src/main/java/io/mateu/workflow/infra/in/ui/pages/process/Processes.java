package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.fluent.GridLayout;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
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
// Full width (uncapped) so the process table and the wide monitoring graph use the whole screen.
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class Processes extends Crud<Object, Object, Object, ProcessFilters, ProcessRow, String> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final io.mateu.workflow.application.out.UpstreamEventPublisher upstreamEventPublisher;
    final RetryProcessUseCase retryProcessUseCase;

    @Override
    public Class<ProcessFilters> filtersClass() {
        return ProcessFilters.class;
    }

    @Override
    public Class<ProcessRow> rowClass() {
        return ProcessRow.class;
    }

    // Processes are a scannable, columnar listing — keep it a table rather than letting the auto
    // weight-engine fall back to cards.
    @Override
    public GridLayout gridLayout() {
        return GridLayout.table;
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

    /**
     * Picks the selected processes up where they stopped: the steps that failed or were cancelled
     * run again, the ones that succeeded are left alone.
     *
     * <p>Applied to a selection, so it is applied to whatever the operator ticked — including
     * processes it makes no sense for. The engine is what decides: a process that is not ERROR or
     * CANCELLED is left alone and says so in the log, rather than this page trying to guess which
     * rows qualify from a list that may already be out of date by the time the click lands.
     */
    @ListToolbarButton(rowsSelectedRequired = true)
    @Label("Retry from failure")
    public void retry(List<ProcessRow> selectedRows) {
        selectedRows.forEach(row -> {
            // Requested, not performed here — the process belongs to the pod holding its
            // partition, and this is whichever pod served the click.
            upstreamEventPublisher.publish(new RetryProcessRequested(row.id()));
        });
    }

    /**
     * Runs the selected processes again from the top, including the steps that already succeeded.
     * Asks first: in bulk, this is the more expensive of the two by some margin.
     */
    @ListToolbarButton(rowsSelectedRequired = true, confirmationRequired = true)
    @Label("Restart from the beginning")
    public void restart(List<ProcessRow> selectedRows) {
        selectedRows.forEach(row ->
                upstreamEventPublisher.publish(new RestartProcessRequested(row.id())));
    }

}
