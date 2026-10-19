package io.mateu.workflow.application.flowcontrol;

import io.mateu.workflow.domain.aggregates.StartPolicy;
import io.mateu.workflow.template.Templates;

import java.util.LinkedHashMap;
import java.util.Map;

/** Renders a {@code startPolicy}'s key. */
public final class FlowKeys {

    private FlowKeys() {
    }

    /** A key that did not render, or rendered blank: the start is refused rather than sharing a key with others. */
    public static class UnrenderableKeyException extends IllegalArgumentException {
        public UnrenderableKeyException(String message) {
            super(message);
        }
    }

    /**
     * The key over a start's variables — what a definition's policy sees: the variables by name, plus
     * {@code businessKey} and {@code workflowId}. {@code ""} when the policy declares no key.
     */
    public static String overVariables(StartPolicy policy, String workflowId, String businessKey,
                                       Map<String, String> variables) {
        var context = new LinkedHashMap<String, Object>();
        if (variables != null) {
            context.putAll(variables);
        }
        context.put("businessKey", businessKey);
        context.put("workflowId", workflowId);
        return render(policy, context);
    }

    /** The key over any context (an event's, for a trigger's own policy). */
    public static String render(StartPolicy policy, Map<String, ?> context) {
        if (policy.key() == null) {
            return "";
        }
        String key;
        try {
            key = Templates.renderText(policy.key(), context);
        } catch (Templates.TemplateException | IllegalArgumentException e) {
            throw new UnrenderableKeyException("startPolicy key '" + policy.key() + "' could not be rendered: " + e.getMessage());
        }
        if (key == null || key.isBlank()) {
            throw new UnrenderableKeyException("startPolicy key '" + policy.key() + "' rendered empty");
        }
        return key;
    }
}
