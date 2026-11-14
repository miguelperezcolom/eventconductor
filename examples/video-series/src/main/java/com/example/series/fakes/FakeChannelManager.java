package com.example.series.fakes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LOCAL STAND-IN — the channel manager that sells the hotel's rooms on booking sites, with an outage switch, so
 * the series has incidents to operate (chapter 11) and a way to end them.
 *
 * <ul>
 *   <li>{@code PUT /fake/channel-manager/availability/{room}} — 200 with a version; 503 during an outage</li>
 *   <li>{@code POST /fake/channel-manager/outage?on=true|false} — start or end the outage</li>
 *   <li>{@code GET /fake/channel-manager/outage} — is it down?</li>
 * </ul>
 */
@RestController
@RequestMapping("/fake/channel-manager")
public class FakeChannelManager {

    private final AtomicBoolean outage = new AtomicBoolean(false);
    private final AtomicLong version = new AtomicLong();

    @PutMapping("/availability/{room}")
    public ResponseEntity<Map<String, Object>> push(@PathVariable String room, @RequestBody Map<String, Object> body) {
        if (outage.get()) {
            return ResponseEntity.status(503).body(Map.of("error", "channel manager unavailable"));
        }
        return ResponseEntity.ok(Map.of("room", room, "version", version.incrementAndGet()));
    }

    @PostMapping("/outage")
    public Map<String, Object> outage(@RequestParam boolean on) {
        outage.set(on);
        return Map.of("outage", on);
    }

    @GetMapping("/outage")
    public Map<String, Object> outage() {
        return Map.of("outage", outage.get());
    }
}
