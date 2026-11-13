package com.example.hotel.workers;

/** One room of a booking, as the process carries it: {@code {"number": "101", "type": "double"}}. */
public record Room(String number, String type) {
}
