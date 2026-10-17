package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.application.services.EventTriggerService.TriggerState;
import io.mateu.workflow.application.services.EventTriggerService.TriggerView;
import io.mateu.workflow.domain.aggregates.EventReceipt;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/** How the event pages and the definition's Triggers tab show a trigger and a receipt. */
public final class EventFormats {

    private static final DateTimeFormatter RECEIVED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private EventFormats() {
    }

    /** The state as a badge. */
    public static Status status(TriggerState state) {
        return switch (state) {
            case ACTIVE -> new Status(StatusType.SUCCESS, "Active");
            case PAUSED -> new Status(StatusType.WARNING, "Paused");
            case INACTIVE -> new Status(StatusType.NONE, "Workflow inactive");
            case DUPLICATE -> new Status(StatusType.DANGER, "Duplicate id");
        };
    }

    /** The outcome as a badge. */
    public static Status outcome(EventReceipt.Outcome outcome) {
        return switch (outcome) {
            case STARTED -> new Status(StatusType.SUCCESS, "Started");
            case DELIVERED -> new Status(StatusType.INFO, "Delivered");
            case ERROR -> new Status(StatusType.DANGER, "Error");
        };
    }

    /** "destination → topic", saying so when the destination has no topic configured. */
    public static String destination(TriggerView view) {
        return view.destination() + " → " + (view.topic() == null ? "(no topic configured)" : view.topic());
    }

    /** name=template, in the order written; "data's members" when none is declared. */
    public static String variables(TriggerView view) {
        if (view.variables() == null) {
            return "data's top-level members";
        }
        return view.variables().isEmpty() ? "—" : view.variables().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /** The business key template, or what the engine derives. */
    public static String businessKey(TriggerView view) {
        return view.businessKey() == null ? "<workflow>-evt-<trigger>-<event hash>" : view.businessKey();
    }

    public static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    public static String received(EventReceipt receipt) {
        return RECEIVED.format(receipt.receivedAt());
    }
}
