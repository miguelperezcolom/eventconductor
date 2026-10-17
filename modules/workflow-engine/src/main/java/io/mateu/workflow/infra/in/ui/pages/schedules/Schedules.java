package io.mateu.workflow.infra.in.ui.pages.schedules;

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
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The Schedules page: every scheduled start the engine knows, wherever it is declared — a definition's
 * {@code cronExpression} or {@code schedules}, a {@code cron.yaml} imported from the classpath, a
 * directory or git, or created right here.
 *
 * <p>Creating here is the quick way to have a workflow run on a schedule: the schedule is the engine's
 * (source UI/API), editable and deletable from its page. Once it is right, its page gives the
 * {@code cron.yaml} entry to commit; when that file is imported it owns the schedule — same id, same
 * pause, same business keys — and the copy kept here is deleted. Schedules declared in files are only
 * operated here: paused, resumed, started now.
 *
 * <p>Extends {@code Crud} directly (as {@code Processes} does): the detail is a {@link ScheduleDetailView},
 * not the row.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class Schedules extends Crud<Object, ScheduleForm, ScheduleForm, ScheduleFilters, ScheduleListRow, String> {

    final ScheduleService scheduleService;
    final ObjectProvider<ScheduleDetailView> detailViewProvider;
    final ObjectProvider<CallerResolver> callerResolver;

    @Override
    public Class<ScheduleFilters> filtersClass() {
        return ScheduleFilters.class;
    }

    @Override
    public Class<ScheduleListRow> rowClass() {
        return ScheduleListRow.class;
    }

    @Override
    public GridLayout gridLayout() {
        return GridLayout.table;
    }

    @Override
    public String title() {
        return "Schedules";
    }

    @Override
    public ListingData<ScheduleListRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var filters = searchRequest.filters() instanceof ScheduleFilters f ? f : null;
        var all = scheduleService.list(filters == null ? null : filters.workflowId(), searchRequest.searchText()).stream()
                .filter(v -> filters == null || filters.origin() == null || filters.origin().name().equals(v.origin()))
                .filter(v -> filters == null || filters.state() == null || filters.state() == v.state())
                .map(ScheduleListRow::of)
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
        return ScheduleDetailView.class;
    }

    @Override
    public String getStyleForView() {
        return StyleConstants.FULL_WIDTH_WITH_PADDING;
    }

    /** Only a schedule created here is edited here; the detail hides Edit for the others. */
    @Override
    public ScheduleForm edit(String id, HttpRequest httpRequest) {
        return ScheduleForm.of(scheduleService.get(id));
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(ScheduleForm.class);
        return scheduleService.update(form.id, form.draft(), callerContext()).id();
    }

    @Override
    public Class<ScheduleForm> creationFormClass() {
        return ScheduleForm.class;
    }

    @Override
    public ScheduleForm creationForm(HttpRequest httpRequest) {
        return new ScheduleForm();
    }

    /** Lands on the new schedule's page, which shows its next starts. */
    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(ScheduleForm.class);
        return scheduleService.create(form.draft(), callerContext()).id();
    }

    /** Deleting is done from a schedule's page, where it is only offered for the ones created here. */
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

    /** Pauses the selected schedules, whoever owns them. */
    @ListToolbarButton(rowsSelectedRequired = true)
    @Label("Pause")
    public void pauseSelected(List<ScheduleListRow> selectedRows) {
        var by = caller();
        selectedRows.forEach(row -> scheduleService.pause(row.id(), by));
    }

    /** Resumes the selected schedules; occurrences missed while paused are not replayed. */
    @ListToolbarButton(rowsSelectedRequired = true)
    @Label("Resume")
    public void resumeSelected(List<ScheduleListRow> selectedRows) {
        selectedRows.forEach(row -> scheduleService.resume(row.id()));
    }

    /** The caller as resolved at this request, for the flow-authorization check. */
    private io.mateu.workflow.security.AuthorizationContext callerContext() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    private String caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        var context = resolver == null ? null : resolver.current();
        return context == null ? null : context.subject();
    }
}
