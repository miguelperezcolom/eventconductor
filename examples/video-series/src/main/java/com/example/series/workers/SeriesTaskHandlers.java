package com.example.series.workers;

import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hotel's workers for the series' definitions: the business work behind each ACTION step, as plain Spring
 * beans. An ACTION step with no {@code task:} is served by the handler whose id is the step's id. They return to
 * complete the step and throw {@link TaskFailure} to fail it; they never retry, wait or roll back themselves.
 * Everything here is deterministic, so a recorded run looks the same every time.
 */
@Configuration
public class SeriesTaskHandlers {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SeriesTaskHandlers.class);

    private static <I, O> TaskRegistration<I, O> task(String id, Class<I> in, Class<O> out,
                                                      io.mateu.workflow.worker.api.TaskHandler<I, O> handler) {
        return new TaskRegistration<>(id, 1, "hotel", in, out, handler);
    }

    // --- hotel-booking (chapter 7) ---------------------------------------------------------

    public record NotifyGuestInput(String guest, String bookingNumber) {}
    public record NotifiedAt(String notifiedAt) {}

    @Bean
    TaskRegistration<NotifyGuestInput, NotifiedAt> notifyGuest() {
        return confirming("notify-guest");
    }

    /** The confirmation once the guest has accepted the alternatives and they are booked. */
    @Bean
    TaskRegistration<NotifyGuestInput, NotifiedAt> confirmAlternatives() {
        return confirming("confirm-alternatives");
    }

    private static TaskRegistration<NotifyGuestInput, NotifiedAt> confirming(String id) {
        return task(id, NotifyGuestInput.class, NotifiedAt.class, (input, context) -> {
            log.info("Told {} their booking is confirmed", input.guest());
            return new NotifiedAt(Instant.now().toString());
        });
    }

    public record AskGuestInput(String guest, String roomBookings, String alternatives) {}
    public record AskedAt(String askedAt) {}

    /** A room could not be booked: tell the guest what the hotel offers instead, and ask (message guest-answer). */
    @Bean
    TaskRegistration<AskGuestInput, AskedAt> askGuest() {
        return asking("ask-guest", "Asked");
    }

    @Bean
    TaskRegistration<AskGuestInput, AskedAt> remindGuest() {
        return asking("remind-guest", "Reminded");
    }

    private static TaskRegistration<AskGuestInput, AskedAt> asking(String id, String verb) {
        return task(id, AskGuestInput.class, AskedAt.class, (input, context) -> {
            log.info("{} {} what to do: the hotel offers {}", verb, input.guest(), input.alternatives());
            return new AskedAt(Instant.now().toString());
        });
    }

    // --- group-arrival and prepare-room (chapter 6) ----------------------------------------

    public record GroupInput(String leader, Integer groupSize) {}
    public record Coordinator(String coordinator) {}
    public record WelcomePlan(String welcomePlan) {}
    public record WelcomePacks(Integer welcomePacks) {}

    @Bean
    TaskRegistration<GroupInput, Coordinator> assignCoordinator() {
        return task("assign-coordinator", GroupInput.class, Coordinator.class,
                (input, context) -> new Coordinator("Marta (groups desk)"));
    }

    @Bean
    TaskRegistration<GroupInput, WelcomePlan> standardWelcome() {
        return task("standard-welcome", GroupInput.class, WelcomePlan.class,
                (input, context) -> new WelcomePlan("front desk, standard welcome"));
    }

    @Bean
    TaskRegistration<GroupInput, WelcomePacks> welcomePack() {
        return task("welcome-pack", GroupInput.class, WelcomePacks.class,
                (input, context) -> new WelcomePacks(input.groupSize() == null ? 1 : input.groupSize()));
    }

    @Bean
    TaskRegistration<GroupInput, NotifiedAt> notifyGroup() {
        return task("notify-group", GroupInput.class, NotifiedAt.class, (input, context) -> {
            log.info("Told {} the group's arrival is ready", input.leader());
            return new NotifiedAt(Instant.now().toString());
        });
    }

    public record RoomInput(Room room, Boolean fixed) {}
    public record Clean(String clean) {}
    public record Issues(Integer issues) {}
    public record Fixed(Boolean fixed) {}
    public record RoomStatus(String roomStatus) {}

    /** Times housekeeping was asked about a room, per sub-process: a "suite" is clean on the second ask. */
    private final Map<String, Integer> housekeepingAsks = new ConcurrentHashMap<>();

    @Bean
    TaskRegistration<RoomInput, Clean> checkHousekeeping() {
        return task("check-housekeeping", RoomInput.class, Clean.class, (input, context) -> {
            var asks = housekeepingAsks.merge(context.processId(), 1, Integer::sum);
            var clean = !"suite".equals(input.room().type()) || asks >= 2;
            return new Clean(String.valueOf(clean));
        });
    }

    /** An "out-of-order" room cannot be prepared: a business failure, final. Otherwise its issues, until fixed. */
    @Bean
    TaskRegistration<RoomInput, Issues> inspectRoom() {
        return task("inspect-room", RoomInput.class, Issues.class, (input, context) -> {
            if ("out-of-order".equals(input.room().type())) {
                throw new TaskFailure("OUT_OF_ORDER", "Room " + input.room().number() + " is out of order", false);
            }
            var found = Boolean.TRUE.equals(input.fixed()) || input.room().issues() == null ? 0 : input.room().issues();
            return new Issues(found);
        });
    }

    @Bean
    TaskRegistration<RoomInput, Fixed> fixIssues() {
        return task("fix-issues", RoomInput.class, Fixed.class, (input, context) -> {
            log.info("Fixed the issues of room {}", input.room().number());
            return new Fixed(true);
        });
    }

    @Bean
    TaskRegistration<RoomInput, RoomStatus> stockMinibar() {
        return task("stock-minibar", RoomInput.class, RoomStatus.class,
                (input, context) -> new RoomStatus("Room " + input.room().number() + " ready"));
    }

    // --- airport-transfer (chapter 6) ------------------------------------------------------

    public record TransferInput(String guest, String taxi, String taxiBookingId) {}
    public record PickupSent(String pickupSentAt) {}

    @Bean
    TaskRegistration<TransferInput, PickupSent> confirmTransfer() {
        return task("confirm-transfer", TransferInput.class, PickupSent.class, (input, context) -> {
            log.info("Told {} their taxi is {} ({})", input.guest(), input.taxi(), input.taxiBookingId());
            return new PickupSent(Instant.now().toString());
        });
    }

    // --- balance-reminder, guest-checkin, room-cleaning (chapter 8) ------------------------

    public record BalanceInput(String guest, String balance) {}
    public record PaymentLink(String paymentLink) {}
    public record Guaranteed(Boolean guaranteed) {}
    public record Released(String releasedAt) {}

    @Bean
    TaskRegistration<BalanceInput, PaymentLink> sendPaymentLink() {
        return task("send-payment-link", BalanceInput.class, PaymentLink.class,
                (input, context) -> new PaymentLink("https://pay.example.com/" + context.processId().substring(0, 8)));
    }

    @Bean
    TaskRegistration<BalanceInput, Guaranteed> confirmGuarantee() {
        return task("confirm-guarantee", BalanceInput.class, Guaranteed.class, (input, context) -> new Guaranteed(true));
    }

    @Bean
    TaskRegistration<BalanceInput, Released> releaseRoom() {
        return task("release-room", BalanceInput.class, Released.class,
                (input, context) -> new Released(Instant.now().toString()));
    }

    public record CheckinInput(String guest, String room, String cleanedBy) {}
    public record KeyCard(String keyCard) {}
    public record LoungePass(String loungePass) {}
    public record CleanedBy(String cleanedBy) {}

    @Bean
    TaskRegistration<CheckinInput, KeyCard> handOverKeys() {
        return task("hand-over-keys", CheckinInput.class, KeyCard.class,
                (input, context) -> new KeyCard("KEY-" + input.room()));
    }

    @Bean
    TaskRegistration<CheckinInput, LoungePass> offerLounge() {
        return task("offer-lounge", CheckinInput.class, LoungePass.class,
                (input, context) -> new LoungePass("LOUNGE-" + input.room()));
    }

    @Bean
    TaskRegistration<CheckinInput, CleanedBy> cleanRoom() {
        return task("clean-room", CheckinInput.class, CleanedBy.class, (input, context) -> {
            log.info("Room {} cleaned", input.room());
            return new CleanedBy("Lucía (housekeeping)");
        });
    }

    // --- nightly-occupancy (schedule), loyalty-statement (CONTINUE_AS_NEW) -----------------

    public record HotelInput(String hotel) {}
    public record Occupancy(Integer occupiedRooms, Integer totalRooms) {}

    @Bean
    TaskRegistration<HotelInput, Occupancy> countOccupancy() {
        return task("count-occupancy", HotelInput.class, Occupancy.class, (input, context) -> new Occupancy(37, 52));
    }

    public record MemberInput(String member, Integer month, Integer points) {}
    public record Points(Integer points) {}

    @Bean
    TaskRegistration<MemberInput, Points> addPoints() {
        return task("add-points", MemberInput.class, Points.class,
                (input, context) -> new Points((input.points() == null ? 0 : input.points()) + 120));
    }

    // --- booking-onboarding, room-quote (chapter 9) ----------------------------------------

    public record GuestInput(String guest, String bookingId) {}
    public record GuestId(String guestId) {}

    @Bean
    TaskRegistration<GuestInput, GuestId> registerGuest() {
        return task("register-guest", GuestInput.class, GuestId.class,
                (input, context) -> new GuestId("G-" + Math.abs(String.valueOf(input.bookingId()).hashCode() % 100000)));
    }

    public record QuoteInput(String roomType, Integer nights, String quoteId) {}
    public record Quote(String quoteId, String price, String currency) {}
    public record Expired(String expiredAt) {}

    private static final Map<String, Integer> NIGHTLY = Map.of("double", 120, "suite", 260, "penthouse", 900);

    @Bean
    TaskRegistration<QuoteInput, Quote> priceStay() {
        return task("price-stay", QuoteInput.class, Quote.class, (input, context) -> {
            var nightly = NIGHTLY.get(input.roomType());
            if (nightly == null) {
                throw new TaskFailure("UNKNOWN_ROOM_TYPE", "No price for room type " + input.roomType(), false);
            }
            var nights = input.nights() == null ? 1 : input.nights();
            return new Quote("Q-" + UUID.nameUUIDFromBytes(context.processId().getBytes()).toString().substring(0, 6),
                    String.valueOf(nightly * nights), "EUR");
        });
    }

    @Bean
    TaskRegistration<QuoteInput, Expired> expireQuote() {
        return task("expire-quote", QuoteInput.class, Expired.class,
                (input, context) -> new Expired(Instant.now().toString()));
    }

    // --- special-request-agent (chapter 10) ------------------------------------------------

    public record PreparationInput(String guest, String preparation) {}
    public record AlertedAt(String alertedAt) {}
    public record NotedAt(String notedAt) {}

    @Bean
    TaskRegistration<PreparationInput, AlertedAt> alertFrontDesk() {
        return task("alert-front-desk", PreparationInput.class, AlertedAt.class, (input, context) -> {
            log.info("Front desk alerted for {}: {}", input.guest(), input.preparation());
            return new AlertedAt(Instant.now().toString());
        });
    }

    @Bean
    TaskRegistration<PreparationInput, NotedAt> nothingToDo() {
        return task("nothing-to-do", PreparationInput.class, NotedAt.class,
                (input, context) -> new NotedAt(Instant.now().toString()));
    }
}
