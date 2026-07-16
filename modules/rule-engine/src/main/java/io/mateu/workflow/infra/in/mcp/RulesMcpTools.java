package io.mateu.workflow.infra.in.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.application.services.RuleValidator;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleCommand;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.mcp.McpSystemContext;
import io.mateu.workflow.mcp.McpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RulesMcpTools implements McpTools, McpSystemContext {

    @Override
    public String getSystemContext() {
        return """
                Motor de reglas de negocio:
                - Puedes listar, consultar, guardar, validar y borrar definiciones de reglas.
                - Puedes evaluar una regla contra un conjunto de hechos (facts) en JSON.
                Hay dos tipos de regla: 'expression' (condición when + asignaciones then, en JEXL)
                y 'decision-table' (columnas inputs/outputs y filas de casos, hitPolicy FIRST o COLLECT).
                Las reglas pueden llevar tags y salience (prioridad) para evaluación en grupo.
                """;
    }

    private final RuleRepository ruleRepository;
    private final RuleValidator ruleValidator;
    private final RuleEvaluator ruleEvaluator;
    private final RuleJsonMapper ruleJsonMapper;
    private final SaveRuleUseCase saveRuleUseCase;
    private final DeleteRuleUseCase deleteRuleUseCase;
    private final ImportRulesFromGitUseCase importRulesFromGitUseCase;
    // Own mapper: headless embedders may not expose an ObjectMapper bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record RuleSummary(String id, String name, String description, String type, int version,
                              List<String> tags) {}

    @Tool(description = "List all rule definitions available in the rule engine")
    public List<RuleSummary> listRules() {
        log.info("Listing rules");
        return ruleRepository.findAll().stream()
                .map(rule -> new RuleSummary(rule.id(), rule.name(), rule.description(),
                        rule.type() != null ? rule.type().label() : null, rule.version(), rule.tags()))
                .toList();
    }

    @Tool(description = "Get the full definition of a rule as canonical JSON")
    public String getRule(String id) {
        log.info("Getting rule {}", id);
        return ruleRepository.findById(id)
                .map(ruleJsonMapper::toJson)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + id));
    }

    @Tool(description = "Save (create or update) a rule definition given as JSON or YAML. Validates against the rule schema and returns the rule id.")
    public String saveRule(String ruleDefinition) {
        log.info("Saving rule");
        return saveRuleUseCase.handle(new SaveRuleCommand(ruleJsonMapper.toRule(ruleDefinition)));
    }

    @Tool(description = "Validate a rule definition given as JSON or YAML without saving it. Returns 'valid' or the list of violations.")
    public String validateRule(String ruleDefinition) {
        log.info("Validating rule");
        try {
            ruleValidator.validate(ruleJsonMapper.toRule(ruleDefinition));
            return "valid";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Tool(description = "Delete a rule definition by id")
    public String deleteRule(String id) {
        log.info("Deleting rule {}", id);
        deleteRuleUseCase.handle(new DeleteRuleCommand(id));
        return "deleted";
    }

    @Tool(description = "Evaluate a rule against a set of facts given as a JSON object (e.g. {\"order\": {\"total\": 200}}). Returns whether it matched and the produced outputs.")
    public String evaluateRule(String ruleId, String factsJson) {
        log.info("Evaluating rule {} with facts {}", ruleId, factsJson);
        try {
            Map<String, Object> facts = objectMapper.readValue(factsJson, new TypeReference<>() {
            });
            var result = ruleEvaluator.evaluate(ruleId, facts);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "Evaluation failed: " + e.getMessage();
        }
    }

    @Tool(description = "Import rule definitions from configured Git repositories. Scans each repository for JSON/YAML files that represent rule definitions and upserts them into the system.")
    public String importRulesFromGit() {
        log.info("Importing rule definitions from Git repositories");
        var result = importRulesFromGitUseCase.handle();
        var sb = new StringBuilder();
        if (!result.imported().isEmpty()) {
            sb.append("Imported ").append(result.imported().size()).append(" rule(s):\n");
            result.imported().forEach(name -> sb.append("  - ").append(name).append("\n"));
        } else {
            sb.append("No new rule definitions found.\n");
        }
        if (!result.errors().isEmpty()) {
            sb.append("Errors (").append(result.errors().size()).append("):\n");
            result.errors().forEach(err -> sb.append("  - ").append(err).append("\n"));
        }
        return sb.toString();
    }
}
