package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;
import io.mateu.workflow.infra.in.ui.pages.steps.StepExecutions;

/**
 * The half of the engine's UI that is about running work: what is in flight, and what each step
 * did.
 *
 * <p>Split out of {@link WorkflowMenu} so a deployment with two consoles can mount the two halves
 * on different hosts — operations where the work is done, {@link WorkflowAdminMenu} behind whatever
 * gate administration sits behind. {@code WorkflowMenu} itself is unchanged and still offers all
 * four sections, because it is the navigation contract an embedding application already mounts;
 * an embedder that wants everything keeps getting everything, and one that wants the split mounts
 * these two instead.
 *
 * <p>The field names are the same as in {@code WorkflowMenu} on purpose: Mateu builds a menu
 * entry's route from the field name, so {@code /workflow/processes} stays
 * {@code /workflow/processes} after the split. Deep links, the chat agent's NAVIGATE blocks and
 * anything else holding a route keep working.
 */
public class WorkflowOperationsMenu {

    @Menu
    Processes processes;

    @Menu
    StepExecutions steps;

}
