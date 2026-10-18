package io.mateu.workflow.infra.in.ui.pages.incidents;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.fluent.GridLayout;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.infra.in.ui.pages.events.EventFormats;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Incidents: the failures waiting for an operator — the failed steps of failed processes — grouped by
 * workflow, step and error family, most failures first. A row opens the group: its processes, and
 * "Retry all". See {@link IncidentService}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class Incidents extends Crud<Object, Object, Object, IncidentFilters, IncidentRow, String> {

    final IncidentService incidentService;
    final ObjectProvider<IncidentDetailView> detailViewProvider;

    @Override
    public Class<IncidentFilters> filtersClass() {
        return IncidentFilters.class;
    }

    @Override
    public Class<IncidentRow> rowClass() {
        return IncidentRow.class;
    }

    @Override
    public GridLayout gridLayout() {
        return GridLayout.table;
    }

    @Override
    public String title() {
        return "Incidents";
    }

    @Override
    public ListingData<IncidentRow> search(SearchRequest searchRequest, HttpRequest httpRequest) {
        var filters = searchRequest.filters() instanceof IncidentFilters f ? f : null;
        var text = searchRequest.searchText();
        var needle = text == null || text.isBlank() ? null : text.toLowerCase(Locale.ROOT);
        var rows = incidentService.incidents(filters == null ? null : filters.workflowId()).groups().stream()
                .map(IncidentRow::of)
                .filter(row -> needle == null || (row.step() + " " + row.error() + " " + row.workflow())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return EventFormats.page(rows, text, searchRequest.pageable());
    }

    @Override
    public Object view(String id, HttpRequest httpRequest) {
        return detailViewProvider.getObject().load(id);
    }

    @Override
    public Class<?> viewClass() {
        return IncidentDetailView.class;
    }

    @Override
    public Object edit(String id, HttpRequest httpRequest) {
        return view(id, httpRequest);
    }

    @Override
    public Object creationForm(HttpRequest httpRequest) {
        throw new UnsupportedOperationException();
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
    public boolean canCreate() {
        return false;
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
        return true;
    }
}
