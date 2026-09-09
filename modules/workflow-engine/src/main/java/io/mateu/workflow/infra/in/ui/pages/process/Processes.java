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
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// Mateu 271 folded the old CrudAdapter/AutoNamedView extension points into the Crud page and
// made Navigable<Detail,Id>.view() return the (generic) Detail type. The process detail is a
// rich SimpleProcessViewModel that differs from the ProcessRow shown in the list, so this page
// extends Crud directly with Object view/editor types (as the old adapter did, which returned
// Object) instead of AutoCrud/FilteredAutoCrud, which pin View = Row. The creation form is named
// (CreateProcessForm) so the Crud renders its fields; create() below turns a submit into a process.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
// Full width (uncapped) so the process table and the wide monitoring graph use the whole screen.
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class Processes extends Crud<Object, Object, CreateProcessForm, ProcessFilters, ProcessRow, String> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final io.mateu.workflow.application.services.CommandDispatcher commandDispatcher;
    final RetryProcessUseCase retryProcessUseCase;
    final CreateProcessUseCase createProcessUseCase;
    final CallerResolver callerResolver;

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
    public Class<CreateProcessForm> creationFormClass() {
        return CreateProcessForm.class;
    }

    @Override
    public CreateProcessForm creationForm(HttpRequest httpRequest) {
        return (CreateProcessForm) processCrudAdapter.getCreationForm(httpRequest);
    }

    @Override
    public String save(HttpRequest httpRequest) {
        // Editing a process is not a thing — the detail view is read-only — so the Crud's save,
        // which only ever fires from an edit form, has no work here.
        throw new UnsupportedOperationException();
    }

    /**
     * Starts a process from the "new process" form. The Crud's own Create button fires this (the
     * reserved {@code create} action) — the creation form's fields arrive in the component state,
     * from which they are read back typed. It goes through the same door an upstream event does:
     * the input is bounds-checked here, since a value pasted into a page reaches the same columns,
     * and the caller is resolved at the request rather than on whatever thread the use case runs on.
     *
     * <p>Returns the new id, which is what the Crud navigates to — landing on the new process's
     * detail — so the form does not have to describe where to go next.
     */
    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(CreateProcessForm.class);
        InputLimits.checkIdentifier(form.workflowDefinitionId, "workflowDefinitionId");
        InputLimits.checkIdentifier(form.businessKey, "businessKey");
        InputLimits.checkNamedValues(form.variables, Variable::name, Variable::value, "this process");

        var processId = UUID.randomUUID().toString();
        createProcessUseCase.handle(new CreateProcessCommand(
                processId,
                form.workflowDefinitionId,
                form.businessKey,
                form.variables,
                null,
                callerResolver.current()));
        return processId;
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
            commandDispatcher.dispatch(new RetryProcessRequested(row.id()));
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
                commandDispatcher.dispatch(new RestartProcessRequested(row.id())));
    }

    /**
     * Stops the selected processes: each one and its live steps are marked cancelled and the
     * workers are told. Asks first — a running process is being stopped mid-flight, and in bulk
     * that is a lot of work abandoned at once.
     *
     * <p>Like retry and restart, applied to whatever the operator ticked. A process that is
     * already finished is left alone by the engine rather than filtered out here, where the list
     * may be out of date by the time the click lands.
     */
    @ListToolbarButton(rowsSelectedRequired = true, confirmationRequired = true)
    @Label("Cancel")
    public void cancel(List<ProcessRow> selectedRows) {
        selectedRows.forEach(row ->
                commandDispatcher.dispatch(new ProcessCancellationRequested(null, row.id())));
    }

}
