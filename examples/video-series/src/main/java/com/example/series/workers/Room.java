package com.example.series.workers;

/**
 * One room, as the processes carry it: {@code {"number": "101", "type": "double"}}. {@code issues} is what an
 * inspection finds in it (prepare-room), absent for none.
 */
public record Room(String number, String type, Integer issues) {
}
