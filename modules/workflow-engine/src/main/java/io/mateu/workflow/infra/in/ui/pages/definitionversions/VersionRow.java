package io.mateu.workflow.infra.in.ui.pages.definitionversions;

import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.interfaces.Identifiable;

/**
 * One row in the "Versions" list of a definition: an engine-recorded version with its creation date
 * and how many processes ran with it. {@code id} is {@code "<definitionId>:<version>"} (or
 * {@code "<definitionId>:legacy"}) so the row-click can recover both to open the version detail.
 */
public record VersionRow(
        @Hidden String id,
        @Label("Version") String version,
        @Label("Created") String created,
        @Label("Running") long running,
        @Label("Completed") long completed,
        @Label("Total processes") long total
) implements Identifiable {
}
