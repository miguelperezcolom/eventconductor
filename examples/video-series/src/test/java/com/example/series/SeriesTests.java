package com.example.series;

/** What the kit tests share, so they share one Spring context too. */
final class SeriesTests {

    /**
     * The nightly schedule (cron.yaml) off: a test that moves the clock by a month would otherwise start thirty
     * nightly reports. ScheduleTest runs with it on.
     */
    static final String NO_CRON = "workflow.cron-enabled=false";

    private SeriesTests() {
    }
}
