package io.mateu.workflow.booking.application.usecases.booking.delete;

import java.util.List;

public record DeleteBookingCommand(List<String> ids) {
}
