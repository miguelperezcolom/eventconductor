package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.fluent.GridLayout;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The Event triggers page: every event trigger the engine knows — which domain events start which
 * workflow — wherever it is declared: a definition's {@code triggers}, an {@code events.yaml} imported
 * from the classpath, a directory or git, or created right here.
 *
 * <p>Creating here is the quick way to link an event to a workflow: the trigger is the engine's (source
 * UI/API), editable and deletable from its page. Once it is right, its page gives the {@code events.yaml}
 * entry to commit; when that file is imported it owns the trigger — same key, same pause — and the copy
 * kept here is deleted. Triggers declared in files are only operated here: paused and resumed. What the
 * received events did is on the Received events page.
 *
 * <p>Extends {@code Crud} directly (as {@code Schedules} does): the detail is an
 * {@link EventTriggerDetailView}, not the row.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class EventTriggers extends Crud<Object, EventTriggerForm, EventTriggerForm, EventTriggerFilters, EventTriggerRow, String> {

    final EventTriggerService eventTriggerService;
    final ObjectProvider<EventTriggerDetailView> detailViewProvider;
    final ObjectProvider<CallerResolver> callerResolver;

    @Override
    public Class<EventTriggerFilters> filtersClass() {
        return EventTriggerFilters.class;
    }

    @Override
    public Class<EventTriggerRow> rowClass() {
        return EventTriggerRow.class;
    }

    @Override
    public GridLayout gridLayout() {
        return GridLayout.table;
    }

    @Override
    public String title() {
        return "Event triggers";
    }

    @Override
    public ListingData<EventTriggerRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var filters = searchRequest.filters() instanceof EventTriggerFilters f ? f : null;
        var all = eventTriggerService.list(filters == null ? null : filters.workflowId(), searchRequest.searchText()).stream()
                .filter(v -> filters == null || filters.origin() == null || filters.origin().name().equals(v.origin()))
                .filter(v -> filters == null || filters.state() == null || filters.state() == v.state())
                .map(EventTriggerRow::of)
                .toList();
        // The page SIZE is the one asked for, not the rows this page happens to carry (see WorkflowDefinitions).
        var pageable = searchRequest.pageable();
        int size = pageable.size() > 0 ? pageable.size() : all.size();
        int lastPage = size > 0 ? Math.max(0, (all.size() - 1) / size) : 0;
        int pageNumber = Math.min(Math.max(pageable.page(), 0), lastPage);
        var content = all.stream().skip((long) pageNumber * size).limit(size).toList();
        return new ListingData<>(new Page<>(searchRequest.searchText(), size, pageNumber, all.size(), content));
    }

    @Override
    public Object view(String id, HttpRequest httpRequest) {
        return detailViewProvider.getObject().load(id);
    }

    @Override
    public Class<?> viewClass() {
        return EventTriggerDetailView.class;
    }

    @Override
    public String getStyleForView() {
        return StyleConstants.FULL_WIDTH_WITH_PADDING;
    }

    /** Only a trigger created here is edited here; the detail hides Edit for the others. */
    @Override
    public EventTriggerForm edit(String id, HttpRequest httpRequest) {
        return EventTriggerForm.of(eventTriggerService.get(EventTriggerRow.workflowOf(id), EventTriggerRow.triggerOf(id)));
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(EventTriggerForm.class);
        return EventTriggerRow.rowId(eventTriggerService.update(form.workflowId, form.id, form.draft()));
    }

    @Override
    public Class<EventTriggerForm> creationFormClass() {
        return EventTriggerForm.class;
    }

    @Override
    public EventTriggerForm creationForm(HttpRequest httpRequest) {
        return new EventTriggerForm();
    }

    /** Lands on the new trigger's page. */
    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(EventTriggerForm.class);
        return EventTriggerRow.rowId(eventTriggerService.create(form.draft()));
    }

    /** Deleting is done from a trigger's page, where it is only offered for the ones created here. */
    @Override
    public void deleteAllById(List<String> ids, HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canDelete() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    /** Pauses the selected triggers: matching events start nothing, and are not replayed on resume. */
    @ListToolbarButton(rowsSelectedRequired = true)
    @Label("Pause")
    public void pauseSelected(List<EventTriggerRow> selectedRows) {
        var by = caller();
        selectedRows.forEach(row -> eventTriggerService.pause(row.workflow(), row.trigger(), by));
    }

    /** Resumes the selected triggers. */
    @ListToolbarButton(rowsSelectedRequired = true)
    @Label("Resume")
    public void resumeSelected(List<EventTriggerRow> selectedRows) {
        selectedRows.forEach(row -> eventTriggerService.resume(row.workflow(), row.trigger()));
    }

    private String caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        var context = resolver == null ? null : resolver.current();
        return context == null ? null : context.subject();
    }
}
