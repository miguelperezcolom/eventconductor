package io.mateu.workflow.booking.application.usecases.booking.changestatus;

import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;

public record ChangeBookingStatusCommand(String id, BookingStatus status, String taskExecutionId,
                                         String processId) {
}
