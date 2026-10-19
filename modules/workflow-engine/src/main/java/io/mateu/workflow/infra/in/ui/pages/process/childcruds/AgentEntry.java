package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.uidl.annotations.HiddenInList;
import io.mateu.uidl.interfaces.Identifiable;

/**
 * One line of an agent's transcript, as the process detail's Agent tab lists it: the goal, what the
 * model said (and which tools it asked for), and what each call returned — redacted and capped as the
 * engine keeps it on the agent's execution.
 *
 * @param step    the AGENT_CALL step
 * @param turn    the model turn it belongs to (empty for the goal)
 * @param role    user (the goal), assistant (the model) or tool (a call's result)
 * @param tool    for a tool line, the tool; for an assistant line, the calls it asked for
 * @param content the text, or the call's result
 * @param outcome for a tool line: completed, failed, rejected, denied or running
 */
public record AgentEntry(@HiddenInList String processId, @HiddenInList String id, String step, String turn,
                         String role, String tool, String content, String outcome) implements Identifiable {
}
