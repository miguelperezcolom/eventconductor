package io.mateu.workflow.domain.aggregates;

/**
 * Where a step's node sits on the diagram, as the author placed it.
 *
 * <p>Presentation, not behaviour: nothing in the engine reads these numbers. They exist so the
 * arrangement someone made in the IDE survives into the file, into git and into the console,
 * instead of every viewer getting whatever the auto-layout produces that day.
 *
 * <p>Whole pixels on purpose. The layout engine works in doubles, and rounding is what keeps the
 * file readable and its diffs stable — a drag that lands half a pixel away should not show up in a
 * code review.
 *
 * @param x distance from the canvas' left edge to the node's left edge.
 * @param y distance from its top edge to the node's top edge.
 */
public record NodePosition(int x, int y) {
}
