package io.mateu.workflow.controlplaneservice.infra.in.ui;

/**
 * Shared inline-style tokens for the control-plane dashboard. Deliberately identical to the engine's
 * {@code io.mateu.workflow.infra.in.ui.HomeStyles} so the aggregated shell looks coherent across
 * micro-frontends: real KPI cards (small uppercase label over a big value) and bounded, captioned
 * chart cards, with a tight vertical rhythm instead of the wide default form-layout gaps.
 */
public final class HomeStyles {

    private HomeStyles() {}

    public static final String PAGE =
            "max-width: 1080px; margin: auto; display: block; --vaadin-form-layout-row-spacing: 1rem;";

    public static final String KPI_ROW =
            "width: 100%; display: flex; flex-wrap: wrap; gap: 0.9rem; margin-bottom: 0.25rem;";

    public static final String KPI_CARD =
            "flex: 1 1 8.5rem; min-width: 8.5rem; box-sizing: border-box;"
            + " border: 1px solid var(--lumo-contrast-10pct); border-radius: 14px;"
            + " padding: 1rem 1.25rem; background: var(--lumo-base-color);"
            + " box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);";

    public static final String KPI_LABEL =
            "display: block; font-size: var(--lumo-font-size-xs); text-transform: uppercase;"
            + " letter-spacing: 0.05em; font-weight: 600; color: var(--lumo-secondary-text-color); margin: 0;";

    public static final String KPI_VALUE =
            "display: block; font-size: 2rem; font-weight: 700; line-height: 1.15;"
            + " color: var(--lumo-body-text-color); margin-top: 0.35rem;";

    public static final String CHART_ROW =
            "width: 100%; display: flex; flex-wrap: wrap; gap: 0.9rem;"
            + " align-items: stretch; margin-bottom: 1.25rem;";

    public static final String CHART_CARD =
            "flex: 1 1 240px; min-width: 240px; box-sizing: border-box;"
            + " border: 1px solid var(--lumo-contrast-10pct); border-radius: 14px;"
            + " padding: 0.75rem 1rem 0.6rem; background: var(--lumo-base-color);";

    public static final String CHART_TITLE =
            "display: block; font-size: var(--lumo-font-size-xs); text-transform: uppercase;"
            + " letter-spacing: 0.05em; font-weight: 600; color: var(--lumo-secondary-text-color);"
            + " margin: 0 0 0.5rem;";

    public static final String CHART = "height: 220px; width: 100%;";
}
