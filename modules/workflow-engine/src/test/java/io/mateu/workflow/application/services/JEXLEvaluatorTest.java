package io.mateu.workflow.application.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JEXLEvaluatorTest {

    @Test
    void evaluatesSimpleMapExpression() {
        Map<String, Object> ctx = Map.of("x", 5, "threshold", 3);
        Object result = JEXLEvaluator.eval("x > threshold", ctx);
        assertThat(result).isEqualTo(true);
    }

    @Test
    void evaluatesFalseExpression() {
        Map<String, Object> ctx = Map.of("x", 1, "threshold", 10);
        Object result = JEXLEvaluator.eval("x > threshold", ctx);
        assertThat(result).isEqualTo(false);
    }

    @Test
    void evaluatesStringConcatenation() {
        Map<String, Object> ctx = Map.of("name", "World");
        Object result = JEXLEvaluator.eval("'Hello ' + name", ctx);
        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    void evaluatesListExpression() {
        List<String> list = List.of("a", "b", "c");
        Object result = JEXLEvaluator.eval("list.size()", list);
        assertThat(result).isEqualTo(3);
    }

    @Test
    void evaluatesNullContext() {
        Object result = JEXLEvaluator.eval("1 + 1", null);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void evaluatesLiteralExpression() {
        Object result = JEXLEvaluator.eval("42", null);
        assertThat(result).isEqualTo(42);
    }

    @Test
    void evaluatesBooleanLiteral() {
        Object result = JEXLEvaluator.eval("true", null);
        assertThat(result).isEqualTo(true);
    }
}
