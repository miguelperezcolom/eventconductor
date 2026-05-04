package io.mateu.workflow.booking.application.out.query;

import io.mateu.workflow.booking.application.out.query.dto.BookingDto;
import io.mateu.workflow.booking.application.out.query.dto.BookingRow;

public interface BookingQueryService extends QueryService<BookingDto, BookingRow, String> {
}
