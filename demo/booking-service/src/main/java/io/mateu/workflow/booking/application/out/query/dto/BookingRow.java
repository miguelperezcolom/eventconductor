package io.mateu.workflow.booking.application.out.query.dto;

import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;

import java.time.LocalDateTime;

public record BookingRow(String id, String name, LocalDateTime created, BookingStatus status) {
}
