package com.example.series.fakes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LOCAL STAND-IN — two taxi companies and a coach company, so the airport-transfer race and the group transfer
 * have real HTTP APIs to call without leaving the laptop. City Cabs answers at once, Airport Express a second
 * later, so City Cabs wins the race every time. Bookings are idempotent by {@code Idempotency-Key}.
 *
 * <ul>
 *   <li>{@code POST /fake/taxis/{company}/bookings} — book ({@code city-cabs}, {@code airport-express}, {@code coach})</li>
 *   <li>{@code DELETE /fake/taxis/{company}/bookings/by-ref/{ref}} — cancel; 404 if there is nothing to cancel</li>
 *   <li>{@code GET /fake/taxis/bookings} — what every company holds right now</li>
 * </ul>
 */
@RestController
@RequestMapping("/fake/taxis")
public class FakeTaxis {

    private static final Map<String, Long> DELAY_MS = Map.of("city-cabs", 0L, "airport-express", 1000L, "coach", 0L);

    private final Map<String, Map<String, Object>> byKey = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> byRef = new ConcurrentHashMap<>();

    @PostMapping("/{company}/bookings")
    public ResponseEntity<Map<String, Object>> book(
            @PathVariable String company,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body) throws InterruptedException {
        if (!DELAY_MS.containsKey(company)) {
            return ResponseEntity.notFound().build();
        }
        Thread.sleep(DELAY_MS.get(company));
        if (key != null && byKey.containsKey(key)) {
            return ResponseEntity.ok(byKey.get(key));
        }
        var booking = new LinkedHashMap<String, Object>(body);
        booking.put("company", company);
        booking.put("id", company.substring(0, 2).toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        if (key != null) {
            byKey.put(key, booking);
        }
        byRef.put(company + "/" + body.getOrDefault("ref", body.get("group")), booking);
        return ResponseEntity.status(201).body(booking);
    }

    @DeleteMapping("/{company}/bookings/by-ref/{ref}")
    public ResponseEntity<Void> cancel(@PathVariable String company, @PathVariable String ref) {
        var removed = byRef.remove(company + "/" + ref);
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
}
