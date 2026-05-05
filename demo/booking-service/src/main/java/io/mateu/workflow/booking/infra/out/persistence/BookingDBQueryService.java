package io.mateu.workflow.booking.infra.out.persistence;

import io.mateu.uidl.data.*;
import io.mateu.workflow.booking.application.out.query.BookingQueryService;
import io.mateu.workflow.booking.application.out.query.dto.BookingDto;
import io.mateu.workflow.booking.application.out.query.dto.BookingRow;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class BookingDBQueryService implements BookingQueryService {

    final BookingEntityRepository repository;

    private BookingRow toDomain(BookingEntity entity) {
        return new BookingRow(
                entity.id,
                entity.leadName,
                entity.created.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                mapStatus(entity.getStatus())
        );
    }

    private Status mapStatus(String status) {
        return new Status(switch (status) {
            case "Confirmed" -> StatusType.SUCCESS;
            case "Cancelled" -> StatusType.DANGER;
            case "Pending" -> StatusType.INFO;
            default -> StatusType.NONE;
        }, status);
    }

    @Override
    public String getLabel(String id) {
        return repository.findById(id).map(BookingEntity::getLeadName).orElse("Unknown");
    }

    @Override
    public Optional<BookingDto> getById(String id) {
        return repository.findById(id).map(this::toDto);
    }

    private BookingDto toDto(BookingEntity entity) {
        return new BookingDto(
                entity.id,
                entity.leadName,
                entity.created,
                BookingStatus.valueOf(entity.status)
        );
    }

    @Override
    public ListingData<BookingRow> findAll(String searchText,
                                            Object filters, Pageable pageable) {
        var page = repository.findAllByLeadNameContainingIgnoreCaseOrderByCreatedDesc(searchText, org.springframework.data.domain.Pageable
                .ofSize(pageable.size())
                .withPage(pageable.page())
        );
        return new ListingData(new Page(searchText, page.getSize(), page.getNumber(), page.getTotalElements(),
                page.getContent().stream().map(this::toDomain).toList()));
    }

}