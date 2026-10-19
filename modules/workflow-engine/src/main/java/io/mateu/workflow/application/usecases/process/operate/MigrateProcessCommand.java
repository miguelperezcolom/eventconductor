package io.mateu.workflow.application.usecases.process.operate;

import java.util.Map;

/**
 * @param targetVersion   the version of the process's definition to move to; null for the definition as it is now
 * @param stepMapping     source step id → target step id, over the default (same id, compatible type)
 * @param variableMapping variables to set on the process as part of the move
 * @param reason          why the operator did it, for the audit trail
 * @param requestedBy     the operator, as identified at the door; null when nobody was
 */
public record MigrateProcessCommand(String processId, Integer targetVersion, Map<String, String> stepMapping,
                                   Map<String, String> variableMapping, String reason, String requestedBy) {
    public MigrateProcessCommand {
        stepMapping = stepMapping == null ? Map.of() : stepMapping;
        variableMapping = variableMapping == null ? Map.of() : variableMapping;
    }
}
