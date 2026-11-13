package com.example.hotel.workers;

import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;

/**
 * The hotel's workers: the business work behind each ACTION step, as plain Spring beans.
 *
 * <p>Each {@link TaskRegistration} binds a task id to a handler and to the records its variables
 * are bound into. An ACTION step with no {@code task:} is served by the handler whose id is the
 * step's id — so step {@code register-booking} is served by the {@code register-booking} handler.
 * (In a larger project you would declare a task contract and let the Maven plugin generate these
 * types; see the Workers guide.)
 *
 * <p>The handlers return to complete the step and throw {@link TaskFailure} to fail it with a
 * business code. They never retry, wait or roll back themselves: the engine does that.
 */
@Configuration
public class HotelTaskHandlers {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HotelTaskHandlers.class);

    // --- Module 1 --------------------------------------------------------------------------

    public record RegisterBookingInput(String guest, List<Room> rooms) {}
    public record RegisterBookingOutput(String bookingNumber) {}

    @Bean
    TaskRegistration<RegisterBookingInput, RegisterBookingOutput> registerBooking() {
        return new TaskRegistration<>("register-booking", 1, "hotel",
                RegisterBookingInput.class, RegisterBookingOutput.class,
                (input, context) -> {
                    var number = "BK-" + Math.abs(context.processId().hashCode() % 100000);
                    log.info("Registered booking {} for {} ({} rooms)", number, input.guest(),
                            input.rooms() == null ? 0 : input.rooms().size());
                    return new RegisterBookingOutput(number);
                });
    }

    // --- Modules 2 and 3 -------------------------------------------------------------------

    public record CreateRoomBookingInput(Room room) {}
    public record CreateRoomBookingOutput(String roomBookingId) {}

    /** Books one room. A penthouse is always sold out — so module 3 has a failure to look at. */
    @Bean
    TaskRegistration<CreateRoomBookingInput, CreateRoomBookingOutput> createRoomBooking() {
        return new TaskRegistration<>("create-room-booking", 1, "hotel",
                CreateRoomBookingInput.class, CreateRoomBookingOutput.class,
                (input, context) -> {
                    if ("penthouse".equals(input.room().type())) {
                        throw new TaskFailure("SOLD_OUT",
                                "Room " + input.room().number() + " (penthouse) is sold out", false);
                    }
                    var id = "RB-" + input.room().number() + "-" + UUID.randomUUID().toString().substring(0, 4);
                    log.info("Booked room {} → {}", input.room().number(), id);
                    return new CreateRoomBookingOutput(id);
                });
    }

    public record CancelRoomBookingInput(Room room, String roomBookingId) {}
    public record Done() {}

    /** The compensation of a booked room: runs once for every room that was booked. */
    @Bean
    TaskRegistration<CancelRoomBookingInput, Done> cancelRoomBooking() {
        return new TaskRegistration<>("cancel-room-booking", 1, "hotel",
                CancelRoomBookingInput.class, Done.class,
                (input, context) -> {
                    log.info("Cancelled room {} ({})", input.room().number(), input.roomBookingId());
                    return new Done();
                });
    }

    // --- Notifications ---------------------------------------------------------------------

    public record NotifyGuestInput(String guest, String bookingNumber, String confirmationEmail) {}
    public record NotifyGuestOutput(String notifiedAt) {}

    @Bean
    TaskRegistration<NotifyGuestInput, NotifyGuestOutput> notifyGuest() {
        return new TaskRegistration<>("notify-guest", 1, "hotel",
                NotifyGuestInput.class, NotifyGuestOutput.class,
                (input, context) -> {
                    log.info("Told {} their booking is confirmed{}", input.guest(),
                            input.confirmationEmail() == null ? "" : ":\n" + input.confirmationEmail());
                    return new NotifyGuestOutput(java.time.Instant.now().toString());
                });
    }
}
