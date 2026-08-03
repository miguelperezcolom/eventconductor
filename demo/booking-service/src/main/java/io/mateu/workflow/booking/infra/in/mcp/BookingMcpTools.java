package io.mateu.workflow.booking.infra.in.mcp;

import io.mateu.workflow.booking.application.out.query.BookingQueryService;
import io.mateu.workflow.booking.application.usecases.booking.changestatus.ChangeBookingStatusCommand;
import io.mateu.workflow.booking.application.usecases.booking.changestatus.ChangeBookingStatusUseCase;
import io.mateu.workflow.booking.application.usecases.booking.create.CreateBookingCommand;
import io.mateu.workflow.booking.application.usecases.booking.create.CreateBookingUseCase;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import io.mateu.workflow.booking.infra.out.persistence.BookingEntityRepository;
import io.mateu.workflow.mcp.McpSystemContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingMcpTools implements McpSystemContext {

    @Override
    public String getSystemContext() {
        return """
                Servicio de reservas:
                - Puedes crear reservas para un cliente (leadName).
                - Puedes listar y consultar reservas existentes.
                - Puedes cambiar el estado de una reserva.
                Estados válidos de una reserva: Pending, Confirmed, Cancelled.
                Cada reserva tiene un ID único, un nombre de cliente (leadName), fecha de creación y estado.
                """;
    }


    private final BookingQueryService bookingQueryService;
    private final BookingEntityRepository bookingEntityRepository;
    private final CreateBookingUseCase createBookingUseCase;
    private final ChangeBookingStatusUseCase changeBookingStatusUseCase;

    public record BookingSummary(String id, String leadName, String created, String status) {}

    @Tool(description = "Create a new booking for a lead. Returns the generated booking ID")
    public String createBooking(String leadName) {
        log.info("MCP createBooking leadName={}", leadName);
        String id = createBookingUseCase.handle(new CreateBookingCommand(null, leadName));
        return "Booking created with id=%s for leadName=%s".formatted(id, leadName);
    }

    @Tool(description = "List all bookings with their ID, lead name, creation date and status")
    public List<BookingSummary> listBookings() {
        log.info("MCP listBookings");
        return bookingEntityRepository.findAll().stream()
                .map(b -> new BookingSummary(
                        b.getId(), b.getLeadName(),
                        b.getCreated() != null ? b.getCreated().toString() : null,
                        b.getStatus()))
                .toList();
    }

    @Tool(description = "Get full details of a booking by its ID")
    public String getBooking(String id) {
        log.info("MCP getBooking {}", id);
        return bookingQueryService.getById(id)
                .map(b -> "id=%s leadName=%s created=%s status=%s"
                        .formatted(b.id(), b.leadName(), b.created(), b.status()))
                .orElse("Booking not found: " + id);
    }

    @Tool(description = "Change the status of a booking. Valid statuses: Pending, Confirmed, Cancelled")
    public String changeBookingStatus(String id, String status) {
        log.info("MCP changeBookingStatus {} -> {}", id, status);
        try {
            changeBookingStatusUseCase.handle(
                    new ChangeBookingStatusCommand(id, BookingStatus.valueOf(status), "", null));
            return "Booking %s status changed to %s".formatted(id, status);
        } catch (IllegalArgumentException e) {
            return "Invalid status '%s'. Valid values: Pending, Confirmed, Cancelled".formatted(status);
        }
    }
}
