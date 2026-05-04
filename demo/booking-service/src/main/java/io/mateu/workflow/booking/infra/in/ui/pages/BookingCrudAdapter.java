package io.mateu.workflow.booking.infra.in.ui.pages;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.booking.application.out.query.BookingQueryService;
import io.mateu.workflow.booking.application.out.query.dto.BookingRow;
import io.mateu.workflow.booking.application.usecases.booking.delete.DeleteBookingCommand;
import io.mateu.workflow.booking.application.usecases.booking.delete.DeleteBookingUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class BookingCrudAdapter implements CrudAdapter<
        BookingViewModel,
        BookingViewModel,
        BookingViewModel,
        NoFilters,
        BookingRow,
        String
        > {

    final BookingViewModel viewModel;
    final DeleteBookingUseCase deleteResourceUseCase;
    final BookingQueryService queryService;

    @Override
    public ListingData<BookingRow> search(String searchText,
                                           NoFilters filters,
                                           Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteResourceUseCase.handle(new DeleteBookingCommand(selectedIds));
    }

    @Override
    public BookingViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public BookingViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public BookingViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
