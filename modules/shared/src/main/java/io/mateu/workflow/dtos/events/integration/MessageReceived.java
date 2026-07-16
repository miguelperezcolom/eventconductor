package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * External message aimed at a running process (BPMN message catch / Temporal signal).
 * The engine correlates it against MESSAGE steps waiting on {@code messageName}: the
 * process whose correlation key (businessKey by default, or the step's
 * {@code correlationExpression}) equals {@code correlationKey} absorbs {@code variables}
 * and resumes. Messages that match no waiting step are ignored, not buffered.
 */
public record MessageReceived(String messageName, String correlationKey, List<Variable> variables) implements DomainEvent {
}
