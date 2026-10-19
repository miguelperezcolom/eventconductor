package io.mateu.workflow.application.services;

import io.mateu.workflow.analysis.SensitiveVariableRules;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.crypto.VariableEnvelope;
import io.mateu.workflow.domain.aggregates.LogMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Keeps sensitive values out of a process's log — the lines its detail page, {@code GET .../logs} and the
 * MCP tools show, written by steps (an error that quotes a rendered request, a lock key computed from a
 * variable), workers ({@code TaskLogEmitted}) and the engine.
 *
 * <p>Applied where every line is stored ({@code LogMessageRepository.save}): when the process's definition
 * declares sensitive variables, every occurrence of one of their current values in the line is replaced
 * by {@value SensitiveVariableRules#MASK}, and so is any sealed value. Values shorter than
 * {@value #MIN_REDACTED_LENGTH} characters are left alone — redacting "1" or "no" everywhere would make
 * the log unreadable and protect nothing. A process with nothing sensitive costs one map lookup.
 */
@Component
public class SensitiveLogRedaction {

    /** The shortest value redacted from a log line. */
    static final int MIN_REDACTED_LENGTH = 4;

    private static final Pattern SEALED = Pattern.compile(Pattern.quote(VariableEnvelope.PREFIX) + "[A-Za-z0-9_.\\-]+:[A-Za-z0-9_\\-]+");

    private final SensitiveVariablePolicies policies;
    private final ObjectProvider<ProcessRepository> processes;

    public SensitiveLogRedaction(SensitiveVariablePolicies policies, ObjectProvider<ProcessRepository> processes) {
        this.policies = policies;
        this.processes = processes;
    }

    /** The line as it may be stored. */
    public LogMessage redact(LogMessage line) {
        if (line == null || line.getMessage() == null) {
            return line;
        }
        var redacted = redact(line.getProcessId(), line.getMessage());
        return redacted.equals(line.getMessage()) ? line : line.withMessage(redacted);
    }

    /** {@code message} with the process's sensitive values (and any sealed value) masked. */
    public String redact(String processId, String message) {
        if (message == null) {
            return null;
        }
        var text = message.contains(VariableEnvelope.PREFIX)
                ? SEALED.matcher(message).replaceAll(SensitiveVariableRules.MASK) : message;
        var names = policies.ofProcessId(processId);
        if (names.isEmpty()) {
            return text;
        }
        var repository = processes.getIfAvailable();
        var process = repository == null ? null : repository.findById(processId).orElse(null);
        if (process == null || process.getVariables() == null) {
            return text;
        }
        // Longest first, so a value that contains another is masked whole.
        var values = process.getVariables().stream()
                .filter(v -> v != null && v.value() != null && names.test(v.name()))
                .map(v -> v.value())
                .filter(v -> v.length() >= MIN_REDACTED_LENGTH)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (var value : values) {
            text = text.replace(value, SensitiveVariableRules.MASK);
        }
        return text;
    }
}
