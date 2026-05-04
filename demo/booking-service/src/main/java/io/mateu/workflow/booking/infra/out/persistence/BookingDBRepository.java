package io.mateu.workflow.booking.infra.out.persistence;

import io.mateu.workflow.booking.application.out.repository.BookingRepository;
import io.mateu.workflow.booking.domain.aggregates.booking.Booking;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingDBRepository implements BookingRepository {

    final BookingEntityRepository repository;

    @Override
    public Optional<Booking> findById(BookingId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Booking toDomain(BookingEntity entity) {
        return new Booking(
                new BookingId(entity.id),
                new Name(entity.leadName),
                new Time(entity.created),
                BookingStatus.valueOf(entity.status)
        );
    }

    private BookingEntity toEntity(Booking resource) {
        return new BookingEntity(
                resource.getId().id(),
                resource.getLeadName().name(),
                resource.getCreated().time(),
                resource.getStatus().name()
        );
    }

    @Override
    public BookingId save(Booking resource) {
        return new BookingId(repository.save(toEntity(resource)).id);
    }

    @Override
    public void deleteAllById(List<BookingId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(BookingId::id).toList());
    }

}
