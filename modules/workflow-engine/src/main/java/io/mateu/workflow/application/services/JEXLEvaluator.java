package io.mateu.workflow.application.services;

import io.mateu.workflow.expression.ExpressionGuard;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.*;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import java.util.List;
import java.util.Map;

import static io.mateu.core.domain.BasicTypeChecker.isBasic;
import static io.mateu.core.infra.reflection.read.AllEditableFieldsProvider.getAllEditableFields;

@Slf4j
public class JEXLEvaluator {

    /**
     * Safe JexlArithmetic that overrides the contains operator (associated with =~ and !~) to use RE2J (linear-time matching)
     * instead of Java's native backtracking-prone regex matcher, defusing ReDoS CPU-exhaustion attacks.
     *
     * <p>Only the engine changes; the operator keeps the meaning JEXL gives it. {@code =~} against a
     * pattern is an <em>anchored, whole-string</em> match — {@code 'hello world' =~ 'world'} is
     * false, and only {@code '.*world.*'} makes it true — because JEXL's own implementation calls
     * {@code Matcher.matches()}. Reaching for {@code find()} here would quietly turn every existing
     * guard into a substring test and flip the ones written with {@code !~}.
     */
    public static class SafeJexlArithmetic extends JexlArithmetic {
        public SafeJexlArithmetic(boolean astrict) {
            super(astrict);
        }

        @Override
        public Boolean contains(Object container, Object value) {
            if (container == null || value == null) {
                return false;
            }
            if (container instanceof java.util.regex.Pattern || container instanceof String) {
                String patternStr = container instanceof java.util.regex.Pattern p ? p.pattern() : container.toString();
                try {
                    com.google.re2j.Pattern re2jPattern = com.google.re2j.Pattern.compile(patternStr);
                    return re2jPattern.matcher(value.toString()).matches();
                } catch (com.google.re2j.PatternSyntaxException e) {
                    throw new IllegalArgumentException("Invalid regular expression pattern: " + patternStr, e);
                }
            }
            try {
                return super.contains(container, value);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * What a guard is allowed to be, stated rather than assumed.
     *
     * <p>{@code createExpression} already refuses most of this — an expression is not a script, so
     * a loop or an assignment is a parse error there whatever the features say. Declaring them
     * keeps the refusal from depending on that one call: the day a caller reaches for
     * {@code createScript}, or JEXL's expression grammar widens, the denial is still written down
     * here. Loops and lambdas are what would let a guard spin forever on an orchestration thread;
     * {@code newInstance} is a second lock on the door {@link JexlPermissions#RESTRICTED} already
     * holds shut; {@code sideEffectGlobal} is what stops a guard from rewriting the process
     * variables it is supposed to be reading.
     */
    private static final JexlFeatures GUARD_FEATURES = new JexlFeatures()
            .loops(false)
            .lambda(false)
            .newInstance(false)
            .script(false)
            .annotation(false)
            .pragma(false)
            .sideEffectGlobal(false);

    // Expressions come from workflow definitions, which may be imported from git or edited
    // in the UI — treat them as untrusted: RESTRICTED blocks reflection, System, Runtime, etc.
    static JexlEngine jexl = new JexlBuilder()
            .arithmetic(new SafeJexlArithmetic(true))
            .permissions(JexlPermissions.RESTRICTED)
            .features(GUARD_FEATURES)
            .cache(512)
            .strict(true) // Lanza error si una variable no existe (recomendado)
            .create();

    public static Object eval(String expression, Object context) {
        // Size and nesting first, before a parser sees the source: an expression nested thousands
        // deep overflows the parser stack, and a StackOverflowError is an Error — it would sail
        // through every fail-closed `catch (Exception)` around a guard and unwind the orchestration
        // thread instead of failing the one step whose definition was bad. See ExpressionGuard.
        ExpressionGuard.check(expression, "workflow");
        return ExpressionGuard.failClosed("workflow", () -> {
            // 1. Crear la expresión
            //String expression = "user.active && (score > threshold || role == 'admin')";
            JexlExpression e = jexl.createExpression(expression);

            // 2. Crear el contexto con los datos
            JexlContext jc = new MapContext();
            if (context != null) {
                if (context instanceof Map map) {
                    map.keySet().forEach(k -> {
                        Object val = map.get(k);
                        if (val instanceof List<?> list) {
                            jc.set(k.toString(), java.util.Collections.unmodifiableList(list));
                        } else if (val instanceof Map<?, ?> innerMap) {
                            jc.set(k.toString(), java.util.Collections.unmodifiableMap(innerMap));
                        } else {
                            jc.set(k.toString(), val);
                        }
                    });
                } else if (context instanceof List list) {
                    jc.set("list", java.util.Collections.unmodifiableList(list));
                } else if (isBasic(context)) {
                    jc.set("value", context);
                } else {
                    getAllEditableFields(context.getClass()).forEach(field -> {
                        try {
                            Object val = field.get(context);
                            if (val instanceof List<?> list) {
                                jc.set(field.getName(), java.util.Collections.unmodifiableList(list));
                            } else if (val instanceof Map<?, ?> innerMap) {
                                jc.set(field.getName(), java.util.Collections.unmodifiableMap(innerMap));
                            } else {
                                jc.set(field.getName(), val);
                            }
                        } catch (IllegalAccessException ex) {
                            log.error("Cannot read field {} of {}", field.getName(), context.getClass(), ex);
                        }
                    });
                }
            }

            // 3. Evaluar
            return e.evaluate(jc);
        });
    }
}
