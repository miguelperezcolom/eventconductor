package io.mateu.workflow.booking.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
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
@RequiredArgsConstructor
@Scope("prototype")
@Title("Bookings")
public class BookingCrudOrchestrator extends Crud<
        BookingViewModel,
        BookingViewModel,
        BookingViewModel,
        NoFilters,
        BookingRow,
        String
        > {

    final BookingViewModel viewModel;
    final DeleteBookingUseCase deleteBookingUseCase;
    final BookingQueryService queryService;

    @Override
    public ListingData<BookingRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public BookingViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public BookingViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public BookingViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(BookingViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(BookingViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteBookingUseCase.handle(new DeleteBookingCommand(selectedIds));
    }

    @Override
    public String getIdFieldForRow() {
        return "id";
    }
}
