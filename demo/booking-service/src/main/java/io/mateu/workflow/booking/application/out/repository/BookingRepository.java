package io.mateu.workflow.booking.application.out.repository;

import io.mateu.workflow.booking.domain.aggregates.booking.Booking;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;

public interface BookingRepository extends Repository<Booking, BookingId> {
}
