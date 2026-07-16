package io.mateu.workflow.application.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.*;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import java.util.List;
import java.util.Map;

import static io.mateu.core.domain.BasicTypeChecker.isBasic;
import static io.mateu.core.infra.reflection.read.AllEditableFieldsProvider.getAllEditableFields;

@Slf4j
public class JEXLEvaluator {

    // Expressions come from workflow definitions, which may be imported from git or edited
    // in the UI — treat them as untrusted: RESTRICTED blocks reflection, System, Runtime, etc.
    static JexlEngine jexl = new JexlBuilder()
            .permissions(JexlPermissions.RESTRICTED)
            .cache(512)
            .strict(true) // Lanza error si una variable no existe (recomendado)
            .create();

    public static Object eval(String expression, Object context) {
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
    }
}
