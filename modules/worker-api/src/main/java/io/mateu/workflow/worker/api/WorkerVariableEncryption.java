package io.mateu.workflow.worker.api;

import io.mateu.workflow.crypto.SensitiveNames;
import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.crypto.VariableEnvelope;
import io.mateu.workflow.dtos.Variable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A worker that shares the engine's keys: it opens the sensitive variables a task brings (the engine
 * seals them before they leave it — on Kafka, through the HTTP task queue) so the handler binds
 * plaintext, and seals its answer's values again before they travel back.
 *
 * <p>What goes back sealed: every output named like an input that arrived sealed, plus the names (or
 * globs) of {@code eventconductor.worker.encryption.sensitive-variables} — the worker cannot know the
 * definition's policy, so it protects what it was given protected and what it is told to. The engine seals
 * by its own policy at rest anyway.
 *
 * <p>Active when the worker has keys ({@code workflow.encryption.keys.<id>}, or a {@code VariableCodec} /
 * {@code EncryptionKeySource} bean) unless {@code eventconductor.worker.encryption.enabled=false}. A worker
 * without keys passes sealed values through as the {@code ec:enc:v1:…} strings they are.
 */
public final class WorkerVariableEncryption {

    /** Opens nothing, seals nothing: a worker without keys. */
    public static final WorkerVariableEncryption NONE = new WorkerVariableEncryption(VariableEncryption.DISABLED, SensitiveNames.NONE);

    private final VariableEncryption encryption;
    private final SensitiveNames alsoSensitive;

    public WorkerVariableEncryption(VariableEncryption encryption, Collection<String> alsoSensitive) {
        this(encryption, SensitiveNames.of(alsoSensitive == null ? List.of() : List.copyOf(alsoSensitive)));
    }

    private WorkerVariableEncryption(VariableEncryption encryption, SensitiveNames alsoSensitive) {
        this.encryption = encryption == null ? VariableEncryption.DISABLED : encryption;
        this.alsoSensitive = alsoSensitive;
    }

    /** Whether this worker opens and seals at all. */
    public boolean enabled() {
        return encryption.enabled();
    }

    /** What a task brought, opened; the names that arrived sealed. */
    public record Opened(List<Variable> variables, Set<String> sealedNames) {
    }

    /** Opens the variables a task brought. */
    public Opened open(List<Variable> variables) {
        if (!enabled() || variables == null) {
            return new Opened(variables, Set.of());
        }
        var sealed = new LinkedHashSet<String>();
        var opened = new ArrayList<Variable>(variables.size());
        for (var variable : variables) {
            if (variable != null && VariableEnvelope.isSealed(variable.value())) {
                sealed.add(variable.name());
                opened.add(new Variable(variable.name(), encryption.open(variable.value())));
            } else {
                opened.add(variable);
            }
        }
        return new Opened(opened, Set.copyOf(sealed));
    }

    /** Seals the outputs named like a sealed input, or configured sensitive. */
    public List<Variable> seal(List<Variable> outputs, Set<String> sealedNames) {
        if (!enabled() || outputs == null) {
            return outputs;
        }
        return outputs.stream()
                .map(v -> v != null && v.value() != null
                        && ((sealedNames != null && sealedNames.contains(v.name())) || alsoSensitive.test(v.name()))
                        ? new Variable(v.name(), encryption.seal(v.value())) : v)
                .toList();
    }
}
