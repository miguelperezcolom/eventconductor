package io.mateu.testworker.domain;

/** Where the scenario the worker played came from. Recorded against every task it receives. */
public enum ScenarioSource {

    /** The process's own {@code TEST_CONFIG} variable. Wins over everything. */
    TEST_CONFIG,

    /** A row someone saved in the UI, matching this task. */
    OVERRIDE,

    /** Nothing matched: the built-in "take the configured time and complete". */
    DEFAULT
}
