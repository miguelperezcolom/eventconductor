package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

/** Between the wire's variables (on the requested events) and the engine's. Null reads as none. */
public final class OperationVariables {

    private OperationVariables() {
    }

    public static List<Variable> toDomain(List<io.mateu.workflow.dtos.Variable> variables) {
        return variables == null ? List.of()
                : variables.stream().map(v -> new Variable(v.name(), v.value())).toList();
    }

    public static List<io.mateu.workflow.dtos.Variable> toWire(List<Variable> variables) {
        return variables == null ? List.of()
                : variables.stream().map(v -> new io.mateu.workflow.dtos.Variable(v.name(), v.value())).toList();
    }
}
