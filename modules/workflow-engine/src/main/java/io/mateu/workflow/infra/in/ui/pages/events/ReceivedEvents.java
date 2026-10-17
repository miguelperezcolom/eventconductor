package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.core.infra.declarative.orchestrators.crud.FilteredAutoCrud;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.services.EventTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The Received events page: the latest domain events the engine received, newest first, and what each
 * did — the process it started, the waiting step it was delivered to, or why it could not be used.
 * One row per receiver; an event nobody listens for leaves no row. Kept for
 * {@code workflow.events.receipts.retention}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@ReadOnly
public class ReceivedEvents extends FilteredAutoCrud<ReceivedEventFilters, ReceivedEventRow> {

    /** How many receipts the page reads at most. */
    static final int LIMIT = 500;

    final EventTriggerService eventTriggerService;

    @Override
    public Class filtersClass() {
        return ReceivedEventFilters.class;
    }

    @Override
    public String title() {
        return "Received events";
    }

    @Override
    public ListingData<ReceivedEventRow> fetchRows(String searchText, ReceivedEventFilters filters, Pageable pageable,
                                                   HttpRequest httpRequest) {
        var needle = searchText == null || searchText.isBlank() ? null : searchText.toLowerCase(Locale.ROOT);
        var rows = eventTriggerService.received(LIMIT, filters == null ? null : filters.workflowId()).stream()
                .filter(r -> filters == null || filters.outcome() == null || filters.outcome() == r.outcome())
                .filter(r -> needle == null || Stream.of(r.eventType(), r.eventId(), r.eventSource(), r.receiver(),
                                r.businessKey(), r.correlationKey(), r.destination())
                        .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains(needle)))
                .map(ReceivedEventRow::of)
                .toList();
        return EventFormats.page(rows, searchText, pageable);
    }

    @Override
    public CrudStore<ReceivedEventRow> store() {
        return new CrudStore<>() {
            @Override
            public Optional<ReceivedEventRow> findById(String id) {
                return rows().stream().filter(row -> row.id().equals(id)).findFirst();
            }

            @Override
            public String save(ReceivedEventRow entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ReceivedEventRow> findAll() {
                return rows();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private List<ReceivedEventRow> rows() {
        return eventTriggerService.received(LIMIT, null).stream().map(ReceivedEventRow::of).toList();
    }
}
