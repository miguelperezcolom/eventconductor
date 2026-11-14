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

    /** Books one room. A penthouse is always sold out — so module 3 has a room the guest must decide about. */
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

    /** The compensation of a booked room: runs once for every room that was booked — when the guest cancels. */
    @Bean
    TaskRegistration<CancelRoomBookingInput, Done> cancelRoomBooking() {
        return new TaskRegistration<>("cancel-room-booking", 1, "hotel",
                CancelRoomBookingInput.class, Done.class,
                (input, context) -> {
                    log.info("Cancelled room {} ({})", input.room().number(), input.roomBookingId());
                    return new Done();
                });
    }

    // --- The guest decides (modules 3, 4 and 6) ------------------------------------------------

    public record AskGuestInput(String guest, String roomBookings, String alternatives) {}
    public record AskGuestOutput(String askedAt) {}

    /**
     * Tells the guest which rooms could not be booked — and, from module 4 on, what the hotel offers
     * instead — and asks what they want. The answer comes back as the message {@code guest-answer}.
     */
    @Bean
    TaskRegistration<AskGuestInput, AskGuestOutput> askGuest() {
        return askingTheGuest("ask-guest", "Asked");
    }

    /** Module 6: the same message again, a day later, when the guest has not answered. */
    @Bean
    TaskRegistration<AskGuestInput, AskGuestOutput> remindGuest() {
        return askingTheGuest("remind-guest", "Reminded");
    }

    private static TaskRegistration<AskGuestInput, AskGuestOutput> askingTheGuest(String task, String verb) {
        return new TaskRegistration<>(task, 1, "hotel",
                AskGuestInput.class, AskGuestOutput.class,
                (input, context) -> {
                    log.info("{} {} what to do: some rooms could not be booked ({}){}", verb, input.guest(),
                            input.roomBookings(),
                            input.alternatives() == null ? "" : "; the hotel offers " + input.alternatives());
                    return new AskGuestOutput(java.time.Instant.now().toString());
                });
    }

    public record TellFrontDeskInput(String guest, String bookingNumber) {}

    /** No answer from the guest: nothing is cancelled, the front desk is told to call them. */
    @Bean
    TaskRegistration<TellFrontDeskInput, Done> tellFrontDesk() {
        return new TaskRegistration<>("tell-front-desk", 1, "hotel",
                TellFrontDeskInput.class, Done.class,
                (input, context) -> {
                    log.info("Front desk: {} has not answered about booking {} — please call them",
                            input.guest(), input.bookingNumber());
                    return new Done();
                });
    }

    // --- Notifications ---------------------------------------------------------------------

    public record NotifyGuestInput(String guest, String bookingNumber, String confirmationEmail) {}
    public record NotifyGuestOutput(String notifiedAt) {}

    @Bean
    TaskRegistration<NotifyGuestInput, NotifyGuestOutput> notifyGuest() {
        return confirmingToTheGuest("notify-guest");
    }

    /** The confirmation once the guest has decided: rooms kept (module 3 and 6), alternatives booked (6). */
    @Bean
    TaskRegistration<NotifyGuestInput, NotifyGuestOutput> confirmKept() {
        return confirmingToTheGuest("confirm-kept");
    }

    @Bean
    TaskRegistration<NotifyGuestInput, NotifyGuestOutput> confirmAlternatives() {
        return confirmingToTheGuest("confirm-alternatives");
    }

    private static TaskRegistration<NotifyGuestInput, NotifyGuestOutput> confirmingToTheGuest(String task) {
        return new TaskRegistration<>(task, 1, "hotel",
                NotifyGuestInput.class, NotifyGuestOutput.class,
                (input, context) -> {
                    log.info("Told {} their booking is confirmed{}", input.guest(),
                            input.confirmationEmail() == null ? "" : ":\n" + input.confirmationEmail());
                    return new NotifyGuestOutput(java.time.Instant.now().toString());
                });
    }
}
