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
                    map.keySet().forEach(k -> jc.set(k.toString(), map.get(k)));
                } else if (context instanceof List list) {
                    jc.set("list", list);
                } else if (isBasic(context)) {
                    jc.set("value", context);
                } else {
                    getAllEditableFields(context.getClass()).forEach(field -> {
                        try {
                            jc.set(field.getName(), field.get(context));
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
