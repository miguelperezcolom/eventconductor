package io.mateu.workflow.application.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Validates a {@link Rule} against {@code rule-schema.json} plus the semantic
 * checks a schema cannot express: row arity against inputs/outputs and JEXL
 * parseability of every expression.
 *
 * <p>Throws {@link RuleValidationException} if any violation is found.
 */
@Service
@Slf4j
public class RuleValidator {

    private static final String SCHEMA_RESOURCE = "rule-schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final RuleExpressionEvaluator expressionEvaluator = new RuleExpressionEvaluator();
    private final CellConditionCompiler cellConditionCompiler = new CellConditionCompiler();
    private Schema schema;

    @PostConstruct
    public void init() throws IOException {
        try (var stream = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(stream);
            log.debug("Loaded rule JSON schema from classpath:{}", SCHEMA_RESOURCE);
        }
    }

    public void validate(Rule rule) {
        try {
            var violations = schema.validate(objectMapper.writeValueAsString(rule), InputFormat.JSON);
            var details = new ArrayList<>(violations.stream()
                    .map(v -> v.getInstanceLocation() + ": " + v.getMessage())
                    .collect(Collectors.toList()));
            details.addAll(semanticViolations(rule));
            if (!details.isEmpty()) {
                throw new RuleValidationException(
                        "Rule '" + rule.name() + "' is invalid:\n" + String.join("\n", details));
            }
        } catch (RuleValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleValidationException(
                    "Could not validate rule: " + e.getMessage(), e);
        }
    }

    private List<String> semanticViolations(Rule rule) {
        var violations = new ArrayList<String>();
        if (RuleType.EXPRESSION.equals(rule.type())) {
            if (rule.when() != null && !rule.when().isBlank()) {
                checkExpression(rule.when(), "when", violations);
            }
            if (rule.then() != null) {
                rule.then().forEach(assignment ->
                        checkExpression(assignment.expression(), "then." + assignment.name(), violations));
            }
        }
        if (RuleType.DECISION_TABLE.equals(rule.type())
                && rule.inputs() != null && rule.outputs() != null && rule.rows() != null) {
            for (int i = 0; i < rule.rows().size(); i++) {
                var row = rule.rows().get(i);
                if (row.when() == null || row.when().size() != rule.inputs().size()) {
                    violations.add("row " + i + ": 'when' must have " + rule.inputs().size() + " cells (one per input)");
                } else {
                    for (int c = 0; c < row.when().size(); c++) {
                        var condition = cellConditionCompiler.compile(rule.inputs().get(c), row.when().get(c));
                        if (condition != null) {
                            checkExpression(condition, "row " + i + " when[" + c + "]", violations);
                        }
                    }
                }
                if (row.then() == null || row.then().size() != rule.outputs().size()) {
                    violations.add("row " + i + ": 'then' must have " + rule.outputs().size() + " cells (one per output)");
                } else {
                    for (int c = 0; c < row.then().size(); c++) {
                        checkExpression(row.then().get(c), "row " + i + " then[" + c + "]", violations);
                    }
                }
            }
        }
        return violations;
    }

    private void checkExpression(String expression, String where, List<String> violations) {
        try {
            expressionEvaluator.parse(expression);
        } catch (Exception e) {
            violations.add(where + ": invalid JEXL expression '" + expression + "' (" + e.getMessage() + ")");
        }
    }

    public static class RuleValidationException extends RuntimeException {
        public RuleValidationException(String message) {
            super(message);
        }

        public RuleValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
