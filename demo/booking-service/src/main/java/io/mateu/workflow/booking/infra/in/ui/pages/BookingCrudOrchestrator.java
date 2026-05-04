package io.mateu.workflow.booking.infra.in.ui.pages;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.booking.application.out.query.dto.BookingRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Bookings")
public class BookingCrudOrchestrator extends CrudOrchestrator<
        BookingViewModel,
        BookingViewModel,
        BookingViewModel,
        NoFilters,
        BookingRow,
        String
        > {

    final BookingCrudAdapter adapter;

    @Override
    public CrudAdapter<BookingViewModel,
            BookingViewModel, BookingViewModel,
            NoFilters, BookingRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
