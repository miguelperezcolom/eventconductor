package io.mateu.workflow.application.services.messagerouting;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Classifies each message name by how it must be routed (see {@link MessageClassification}),
 * derived from the {@code WAIT_FOR_MESSAGE} steps across every imported workflow definition. Because
 * definitions are replicated to every shard, the classification is reproducible on any shard.
 *
 * <p>The map is cached and rebuilt after an import ({@link #rebuild()}); the classification is on
 * the hot path of every routed message, so it must not query the repository per message. A name the
 * cache has not seen is treated as {@link MessageClassification#EXPRESSION} — the conservative
 * choice, since routing it by business key could miss a waiter on another shard whose version this
 * shard has not imported yet (decision 3). {@code UNKNOWN} is only returned for a name the cache
 * knows no step waits for.
 */
@Service
@RequiredArgsConstructor
public class MessageClassifier {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;

    private volatile Map<String, MessageClassification> byMessageName = Map.of();
    private volatile boolean built = false;

    /** Rebuild the classification from the current definitions. Called after each import. */
    public void rebuild() {
        byMessageName = classify(workflowDefinitionRepository.findAll());
        built = true;
    }

    /** How the given message name is routed. */
    public MessageClassification classify(String messageName) {
        if (!built) {
            rebuild();
        }
        var classification = byMessageName.get(messageName);
        // A name the cache has not seen: EXPRESSION is the safe default (route via subscriptions or
        // broadcast, never by a possibly-wrong business-key placement) unless the cache is complete
        // and simply has no waiter for it, in which case it is genuinely UNKNOWN.
        return classification != null ? classification : MessageClassification.UNKNOWN;
    }

    /**
     * Pure classification of every waited-for message name across the given definitions. A name is
     * {@link MessageClassification#EXPRESSION} if any of its waiters correlates by expression, and
     * {@link MessageClassification#BUSINESS_KEY} when they all use the default correlation.
     */
    static Map<String, MessageClassification> classify(Collection<WorkflowDefinition> definitions) {
        var result = new LinkedHashMap<String, MessageClassification>();
        for (var definition : definitions) {
            if (definition.steps() == null) {
                continue;
            }
            for (Step step : definition.steps()) {
                if (step.type() != StepType.WAIT_FOR_MESSAGE
                        || step.messageName() == null || step.messageName().isBlank()) {
                    continue;
                }
                var name = step.messageName();
                var byExpression = step.correlationExpression() != null
                        && !step.correlationExpression().isBlank();
                var stepClass = byExpression
                        ? MessageClassification.EXPRESSION : MessageClassification.BUSINESS_KEY;
                result.merge(name, stepClass, MessageClassifier::escalate);
            }
        }
        return result;
    }

    /**
     * When two waiters of one name disagree, the less routable wins: EXPRESSION over BUSINESS_KEY,
     * so a name is BUSINESS_KEY only when <em>every</em> waiter uses the default correlation.
     */
    private static MessageClassification escalate(MessageClassification a, MessageClassification b) {
        return a == MessageClassification.EXPRESSION || b == MessageClassification.EXPRESSION
                ? MessageClassification.EXPRESSION : MessageClassification.BUSINESS_KEY;
    }
}
