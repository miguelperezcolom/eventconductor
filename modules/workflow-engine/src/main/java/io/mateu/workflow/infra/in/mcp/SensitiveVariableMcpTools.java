package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.services.SensitiveVariableRevealService;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sensitive variables, as an MCP tool: every process read shows their values as {@code ••••}; revealing
 * one is explicit, needs a reveal role ({@code workflow.security.sensitive.reveal-roles}) and a reason, and
 * is audited in the process's log — the same {@link SensitiveVariableRevealService} the REST API and the
 * console use. A refusal comes back as a sentence saying why.
 */
@Component
public class SensitiveVariableMcpTools implements McpTools, McpSystemContext {

    private final SensitiveVariableRevealService reveal;

    @Autowired(required = false)
    ObjectProvider<CallerResolver> callerResolver;

    public SensitiveVariableMcpTools(SensitiveVariableRevealService reveal) {
        this.reveal = reveal;
    }

    @Override
    public String getSystemContext() {
        return """
                Variables sensibles: los valores de las variables que una definición declara sensitiveVariables se muestran como •••• (cifrados en reposo y en tránsito). Solo revealProcessVariable devuelve uno en claro, con un rol de revelado y un reason obligatorio; queda auditado. No reveles un valor si el usuario no lo pide expresamente.
                """;
    }

    @Tool(description = "Reveal the value of one sensitive variable of a workflow process (shown as •••• everywhere"
            + " else). Needs one of the reveal roles (workflow.security.sensitive.reveal-roles) and a reason; every reveal"
            + " and every refusal is recorded, with who asked and why, in the process's log. Only when the user explicitly"
            + " asks for that value.")
    public String revealProcessVariable(String processId,
                                        @ToolParam(description = "The sensitive variable's name") String name,
                                        @ToolParam(description = "Why the value is needed — mandatory, audited") String reason) {
        try {
            return name + "=" + reveal.reveal(processId, name, reason, caller());
        } catch (RuntimeException e) {
            return "Refused: " + e.getMessage();
        }
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }
}
