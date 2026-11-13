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
 * </ul>
 */
@RestController
@RequestMapping("/fake/hotel/bookings")
public class FakeHotelApi {

    private final Map<String, Map<String, Object>> byKey = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> byRef = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<Map<String, Object>> book(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        if ("penthouse".equals(String.valueOf(body.get("type")))) {
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

    @DeleteMapping("/by-ref/{ref}")
    public ResponseEntity<Void> cancel(@PathVariable String ref) {
        var removed = byRef.remove(ref);
        if (removed == null) {
            return ResponseEntity.notFound().build();
        }
        byKey.values().remove(removed);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Collection<Map<String, Object>> list() {
        return byRef.values();
    }
}
