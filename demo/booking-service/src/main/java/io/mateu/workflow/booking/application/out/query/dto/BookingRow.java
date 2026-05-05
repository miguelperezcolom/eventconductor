package io.mateu.workflow.booking.application.out.query.dto;

import io.mateu.uidl.data.Status;

public record BookingRow(String id, String name,
                         String created,
                         Status status) {
}
