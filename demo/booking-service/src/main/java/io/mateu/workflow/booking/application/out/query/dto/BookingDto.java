package io.mateu.workflow.booking.application.out.query.dto;

import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;

import java.time.LocalDateTime;

public record BookingDto(
        String id,
        String leadName,
        LocalDateTime created,
        BookingStatus status
        ) {
}
