package io.mateu.workflow.infra.in.ui;

/**
 * Shared inline-style tokens for the engine's dashboard pages (home + definition detail). Kept as
 * plain CSS strings because Mateu applies them via {@code @Style} / a component's {@code style} —
 * custom properties inherit through the shadow DOM, so setting them on the page container condenses
 * everything inside. The goal is a consistent, card-based look: real KPI cards (border + subtle
 * shadow, small uppercase label over a big value) and bounded, captioned chart cards, with a tight
 * vertical rhythm instead of the wide default form-layout gaps that left charts floating.
 */
public final class HomeStyles {

    private HomeStyles() {}

    /** Page container: comfortably wide, centred, with a tighter row gap than the Lumo default. */
    public static final String PAGE =
            "max-width: 1080px; margin: auto; display: block; --vaadin-form-layout-row-spacing: 1rem;";

    /** Row of KPI cards — wraps on narrow viewports, even gaps. */
    public static final String KPI_ROW =
            "width: 100%; display: flex; flex-wrap: wrap; gap: 0.9rem; margin-bottom: 0.25rem;";

    /** A single KPI card. */
    public static final String KPI_CARD =
            "flex: 1 1 8.5rem; min-width: 8.5rem; box-sizing: border-box;"
            + " border: 1px solid var(--lumo-contrast-10pct); border-radius: 14px;"
            + " padding: 1rem 1.25rem; background: var(--lumo-base-color);"
            + " box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);";

    /** KPI label: small, dim, uppercase — sits above the value. */
    public static final String KPI_LABEL =
            "display: block; font-size: var(--lumo-font-size-xs); text-transform: uppercase;"
            + " letter-spacing: 0.05em; font-weight: 600; color: var(--lumo-secondary-text-color); margin: 0;";

    /** KPI value: the number, large and bold. */
    public static final String KPI_VALUE =
            "display: block; font-size: 2rem; font-weight: 700; line-height: 1.15;"
            + " color: var(--lumo-body-text-color); margin-top: 0.35rem;";

    /** Row of chart cards. */
    public static final String CHART_ROW =
            "width: 100%; display: flex; flex-wrap: wrap; gap: 0.9rem;"
            + " align-items: stretch; margin-bottom: 1.25rem;";

    /** A single chart card (caption + bounded chart). */
    public static final String CHART_CARD =
            "flex: 1 1 240px; min-width: 240px; box-sizing: border-box;"
            + " border: 1px solid var(--lumo-contrast-10pct); border-radius: 14px;"
            + " padding: 0.75rem 1rem 0.6rem; background: var(--lumo-base-color);";

    /** Chart caption: matches the KPI label. */
    public static final String CHART_TITLE =
            "display: block; font-size: var(--lumo-font-size-xs); text-transform: uppercase;"
            + " letter-spacing: 0.05em; font-weight: 600; color: var(--lumo-secondary-text-color);"
            + " margin: 0 0 0.5rem;";

    /** The chart itself, bounded so it never floats in an over-tall box. */
    public static final String CHART = "height: 220px; width: 100%;";
}
