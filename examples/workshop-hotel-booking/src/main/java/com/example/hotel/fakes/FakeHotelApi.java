package com.example.hotel.fakes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WORKSHOP ONLY — a stand-in for the hotel's booking API, so module 4 has something real to call
 * without leaving the laptop. It honours {@code Idempotency-Key} the way a well-behaved API does:
 * the same key twice returns the same booking instead of creating a second one.
 *
 * <ul>
 *   <li>{@code POST /fake/hotel/bookings} — book a room; a penthouse answers 409 (sold out)</li>
 *   <li>{@code DELETE /fake/hotel/bookings/by-ref/{ref}} — cancel; 404 if there is nothing to cancel</li>
 *   <li>{@code GET /fake/hotel/bookings} — what the hotel holds right now</li>
 *   <li>{@code GET /fake/hotel/alternatives?booking={ref}} — for each room of a booking the hotel had
 *       to refuse, one it can offer instead: the suite next door</li>
 * </ul>
 */
@RestController
@RequestMapping("/fake/hotel")
public class FakeHotelApi {

    private final Map<String, Map<String, Object>> byKey = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> byRef = new ConcurrentHashMap<>();
    /** The rooms the hotel had to refuse, by external reference ({@code BOOKING-7-900}). */
    private final Map<String, Map<String, Object>> refused = new ConcurrentHashMap<>();

    @PostMapping("/bookings")
    public ResponseEntity<Map<String, Object>> book(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if ("penthouse".equals(String.valueOf(body.get("type")))) {
            refused.put(String.valueOf(body.get("externalRef")), body);
            return ResponseEntity.status(409).body(Map.of("error", "sold out"));
        }
        if (key != null && byKey.containsKey(key)) {
            return ResponseEntity.ok(byKey.get(key));                 // a repeat: same booking
        }
        var booking = new LinkedHashMap<String, Object>(body);
        booking.put("id", "H-" + UUID.randomUUID().toString().substring(0, 8));
        booking.put("idempotencyKey", key);
        if (key != null) {
            byKey.put(key, booking);
        }
        byRef.put(String.valueOf(body.get("externalRef")), booking);
        return ResponseEntity.status(201).body(booking);
    }

    @DeleteMapping("/bookings/by-ref/{ref}")
    public ResponseEntity<Void> cancel(@PathVariable String ref) {
        var removed = byRef.remove(ref);
        if (removed == null) {
            return ResponseEntity.notFound().build();
        }
        byKey.values().remove(removed);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bookings")
    public Collection<Map<String, Object>> list() {
        return byRef.values();
    }

    /** What the hotel can offer instead of the rooms of {@code booking} it refused: the suite next door. */
    @GetMapping("/alternatives")
    public List<Map<String, Object>> alternatives(@RequestParam String booking) {
        var offers = new ArrayList<Map<String, Object>>();
        refused.forEach((ref, room) -> {
            if (ref.startsWith(booking + "-")) {
                var number = String.valueOf(room.get("number"));
                var next = number.matches("\\d+") ? String.valueOf(Integer.parseInt(number) + 1) : number + "B";
                offers.add(new LinkedHashMap<>(Map.of("number", next, "type", "suite", "instead", number)));
            }
        });
        return offers;
    }
}
