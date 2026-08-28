package io.mateu.workflow.application.services;

import io.mateu.workflow.expression.ExpressionGuard;
import io.mateu.workflow.expression.LinearTimeRegexArithmetic;
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
            .arithmetic(new LinearTimeRegexArithmetic(true))
            .permissions(JexlPermissions.RESTRICTED)
            .features(GUARD_FEATURES)
            .cache(512)
            // Falsy, not strict: a guard reading a variable nobody set used to throw, every
            // caller fails closed, and BOTH sides of a two-way branch became ineligible — so the
            // process did not take the negative branch, it stopped dead with every downstream step
            // still CREATED and no deadline anywhere to fire. See LinearTimeRegexArithmetic for the
            // other half of this, which is the half that is easy to get wrong.
            .strict(false)
            .create();

    /**
     * What an evaluation produced, and which references it could not resolve.
     *
     * <p>The second half is the whole reason this type exists. Undefined variables are no longer an
     * error, so without recording them a mistyped guard is indistinguishable from a guard that
     * legitimately evaluated to false.
     */
    public record Evaluation(Object value, java.util.Set<String> undefinedVariables) {
    }

    /**
     * A context that notes every name it was asked for and did not have.
     *
     * <p>JEXL consults {@code has} before resolving, so this catches exactly the references that
     * fell through to null — and it short-circuits with the expression, so
     * {@code cancellable && ratePlan == 'X'} reports only {@code cancellable}, which is the one
     * that decided the outcome.
     */
    private static final class RecordingContext extends MapContext {
        private final java.util.Set<String> undefined = new java.util.LinkedHashSet<>();

        @Override
        public boolean has(String name) {
            boolean has = super.has(name);
            if (!has) {
                undefined.add(name);
            }
            return has;
        }
    }

    /** The value alone, for callers that do not care which references were missing. */
    public static Object eval(String expression, Object context) {
        return evaluate(expression, context).value();
    }

    public static Evaluation evaluate(String expression, Object context) {
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
            RecordingContext jc = new RecordingContext();
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
            return new Evaluation(e.evaluate(jc), java.util.Set.copyOf(jc.undefined));
        });
    }
}
