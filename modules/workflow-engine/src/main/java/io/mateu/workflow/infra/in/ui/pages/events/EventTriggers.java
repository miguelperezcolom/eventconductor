package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.core.infra.declarative.orchestrators.crud.FilteredAutoCrud;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Page;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.security.CallerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The Events page: every event trigger the engine knows — which domain events start which workflow —
 * with pause and resume. Triggers are declared in their definitions ({@code triggers}) or in an
 * {@code events.yaml} next to them (the Source column says which); a pause is the engine's, kept by
 * {@code <workflow>/<trigger>} across re-imports. What the received events did is on
 * the Received events page.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@ReadOnly
public class EventTriggers extends FilteredAutoCrud<EventTriggerFilters, EventTriggerRow> {

    final EventTriggerService eventTriggerService;
    final ObjectProvider<CallerResolver> callerResolver;

    @Override
    public Class filtersClass() {
        return EventTriggerFilters.class;
    }

    @Override
    public String title() {
        return "Event triggers";
    }

    @Override
    public ListingData<EventTriggerRow> fetchRows(String searchText, EventTriggerFilters filters, Pageable pageable,
                                                  HttpRequest httpRequest) {
        var rows = eventTriggerService.list(filters == null ? null : filters.workflowId(), searchText).stream()
                .filter(v -> filters == null || filters.state() == null || filters.state() == v.state())
                .map(EventTriggerRow::of)
                .toList();
        return page(rows, searchText, pageable);
    }

    @Override
    public CrudStore<EventTriggerRow> store() {
        return new CrudStore<>() {
            @Override
            public Optional<EventTriggerRow> findById(String id) {
                return rows().stream().filter(row -> row.id().equals(id)).findFirst();
            }

            @Override
            public String save(EventTriggerRow entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<EventTriggerRow> findAll() {
                return rows();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
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

    private List<EventTriggerRow> rows() {
        return eventTriggerService.list(null, null).stream().map(EventTriggerRow::of).toList();
    }

    static <T> ListingData<T> page(List<T> all, String searchText, Pageable pageable) {
        int size = pageable != null && pageable.size() > 0 ? pageable.size() : Math.max(1, all.size());
        int lastPage = Math.max(0, (all.size() - 1) / size);
        int pageNumber = pageable == null ? 0 : Math.min(Math.max(pageable.page(), 0), lastPage);
        var content = all.stream().skip((long) pageNumber * size).limit(size).toList();
        return new ListingData<>(new Page<>(searchText, size, pageNumber, all.size(), content));
    }

    private String caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        var context = resolver == null ? null : resolver.current();
        return context == null ? null : context.subject();
    }
}
