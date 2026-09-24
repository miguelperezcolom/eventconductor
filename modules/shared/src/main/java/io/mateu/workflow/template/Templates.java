package io.mateu.workflow.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.analysis.TemplateSyntax;
import io.mateu.workflow.expression.ExpressionGuard;
import io.mateu.workflow.expression.LinearTimeRegexArithmetic;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payload templates: a process's state rendered into the body of a call, the data of an event or a
 * reply. JEXL expressions in {@code ${…}}, evaluated in the same sandbox as every other expression
 * in a definition (RESTRICTED permissions, no loops, lambdas or side effects, bounded size and
 * nesting) — a template comes from a definition, which may come from git.
 *
 * <p><b>Structured</b> ({@link #render}): maps and lists are walked; a string leaf that is exactly one
 * expression keeps the expression's type (a number stays a number, a list a list — and a variable
 * whose text is a JSON object or array is parsed, since process variables are strings); a string with
 * text around its expressions is interpolated into a string; anything else is a constant. The result is
 * always valid JSON. <b>Text</b> ({@link #renderText}): interpolation only, for XML, form bodies and
 * the like.
 *
 * <p>A template that cannot be rendered throws {@link TemplateException}: a payload is something a
 * caller or a consumer receives, so it never fails into a silently empty value.
 */
public final class Templates {

    private static final JexlFeatures FEATURES = new JexlFeatures()
            .loops(false).lambda(false).newInstance(false).script(false)
            .annotation(false).pragma(false).sideEffectGlobal(false).sideEffect(false);

    private static final JexlEngine JEXL = new JexlBuilder()
            .arithmetic(new LinearTimeRegexArithmetic(true))
            .permissions(JexlPermissions.RESTRICTED)
            .features(FEATURES)
            .cache(512)
            .strict(false)
            .create();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A template that could not be parsed or evaluated. */
    public static final class TemplateException extends RuntimeException {
        public TemplateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Templates() {
    }

    /** Renders a structured template (a map, a list, a string or a constant) against {@code context}. */
    public static Object render(Object template, Map<String, ?> context) {
        if (template instanceof String text) {
            return renderLeaf(text, context);
        }
        if (template instanceof Map<?, ?> map) {
            var rendered = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> rendered.put(String.valueOf(key), render(value, context)));
            return rendered;
        }
        if (template instanceof Collection<?> list) {
            var rendered = new ArrayList<>(list.size());
            list.forEach(item -> rendered.add(render(item, context)));
            return rendered;
        }
        return template;
    }

    /** Renders a text template: every {@code ${…}} replaced by its value's text ({@code null} → empty). */
    public static String renderText(String template, Map<String, ?> context) {
        if (template == null || !TemplateSyntax.isTemplate(template)) {
            return template;
        }
        var out = new StringBuilder();
        for (var segment : parse(template)) {
            if (segment.expression()) {
                var value = evaluate(segment.text(), context);
                out.append(value == null ? "" : textOf(value));
            } else {
                out.append(segment.text());
            }
        }
        return out.toString();
    }

    /** The rendered structured template as JSON text. */
    public static String renderJson(Object template, Map<String, ?> context) {
        try {
            return JSON.writeValueAsString(render(template, context));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new TemplateException("The rendered template is not representable as JSON", e);
        }
    }

    /**
     * Problems with a template's expressions — every one must parse — for validation at build and
     * import time. Undefined variables are not problems: they are runtime data.
     *
     * @param where a label for the messages, e.g. {@code "step 'charge' body"}
     */
    public static List<String> problems(Object template, String where) {
        var problems = new ArrayList<String>();
        List<Map.Entry<String, String>> expressions;
        try {
            expressions = TemplateSyntax.expressions(template, where);
        } catch (TemplateSyntax.TemplateSyntaxException e) {
            return List.of(where + ": " + e.getMessage());
        }
        for (var expression : expressions) {
            try {
                ExpressionGuard.check(expression.getValue(), expression.getKey());
                JEXL.createExpression(expression.getValue());
            } catch (RuntimeException e) {
                problems.add(expression.getKey() + ": ${" + expression.getValue() + "} is not a valid expression ("
                        + firstLine(e.getMessage()) + ")");
            }
        }
        return problems;
    }

    private static Object renderLeaf(String text, Map<String, ?> context) {
        if (!TemplateSyntax.isTemplate(text)) {
            return text;
        }
        var single = TemplateSyntax.singleExpression(text);
        if (single != null) {
            return typed(evaluate(single, context));
        }
        return renderText(text, context);
    }

    /** A string that is a JSON object or array (process variables are strings) becomes that structure. */
    private static Object typed(Object value) {
        if (value instanceof String text) {
            var trimmed = text.strip();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    return JSON.readValue(trimmed, Object.class);
                } catch (Exception notJson) {
                    return text;
                }
            }
        }
        return value;
    }

    private static Object evaluate(String expression, Map<String, ?> context) {
        try {
            ExpressionGuard.check(expression, "template");
            var jexlContext = new MapContext();
            if (context != null) {
                context.forEach(jexlContext::set);
            }
            return JEXL.createExpression(expression).evaluate(jexlContext);
        } catch (RuntimeException e) {
            throw new TemplateException("${" + expression + "} could not be evaluated: " + firstLine(e.getMessage()), e);
        }
    }

    private static List<TemplateSyntax.Segment> parse(String template) {
        try {
            return TemplateSyntax.parse(template);
        } catch (TemplateSyntax.TemplateSyntaxException e) {
            throw new TemplateException(e.getMessage(), e);
        }
    }

    private static String textOf(Object value) {
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "no detail";
        }
        var newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
