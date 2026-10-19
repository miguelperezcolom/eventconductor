package io.mateu.workflow.application.flowcontrol;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mateu.workflow.domain.aggregates.StartPolicy;
import io.mateu.workflow.security.AuthorizationContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One start under a {@code startPolicy}: everything needed to make it now or later — kept as JSON in
 * {@code flow_control_pending} while it waits (a debounce, a throttle queue). The policy rides along as it
 * was when the start arrived, so a start released later follows the rules it was admitted under.
 *
 * @param scope     where the policy counts its keys: {@code trigger:<workflow>/<trigger>} for a trigger's own
 *                  policy, {@code start:<workflow>} for the definition's
 * @param flowKey   the rendered key ({@code ""} when the policy declares none: one key for the scope)
 * @param door      {@code trigger} (an event trigger) or {@code start} (REST, MCP, console, sync invocation)
 * @param triggerId the trigger, for a trigger start; null otherwise
 * @param debounced whether this start already sat out its debounce (it is not debounced again)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowStart(String scope, String flowKey, String workflowId, String businessKey,
                        Map<String, String> variables, AuthorizationContext caller, String door, String triggerId,
                        StartPolicy policy, boolean debounced) {

    public static final String TRIGGER = "trigger";
    public static final String START = "start";

    public FlowStart {
        flowKey = flowKey == null ? "" : flowKey;
        variables = variables == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** The scope of a trigger's own policy. */
    public static String triggerScope(String workflowId, String triggerId) {
        return TRIGGER + ":" + workflowId + "/" + triggerId;
    }

    /** The scope of a definition's policy. */
    public static String startScope(String workflowId) {
        return START + ":" + workflowId;
    }

    /** The same start, past its debounce. */
    public FlowStart asDebounced() {
        return new FlowStart(scope, flowKey, workflowId, businessKey, variables, caller, door, triggerId, policy, true);
    }

    /** The same start under another business key (a deferred start that had none gets one). */
    public FlowStart withBusinessKey(String newBusinessKey) {
        return new FlowStart(scope, flowKey, workflowId, newBusinessKey, variables, caller, door, triggerId, policy,
                debounced);
    }
}
