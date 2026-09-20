package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import io.mateu.uidl.interfaces.Identifiable;

/**
 * A row in the Tasks view: one task contract version, how many workflow definitions reference it,
 * and the URLs a browser downloads the generated worker project from (served by
 * {@code TaskProjectController}).
 */
public record TaskContractRow(
        String id,           // the contract ref, <id>@<version>
        String group,
        String topic,
        String description,
        int workflows,       // how many workflow definitions use this version
        String moduleZip,    // download URL for the task-module project
        String serviceZip)   // download URL for the standalone service project
        implements Identifiable {
}
