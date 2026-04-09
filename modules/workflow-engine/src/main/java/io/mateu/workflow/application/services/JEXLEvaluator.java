package io.mateu.workflow.application.services;

import org.apache.commons.jexl3.*;

import java.util.List;
import java.util.Map;

import static io.mateu.core.domain.BasicTypeChecker.isBasic;
import static io.mateu.core.infra.reflection.read.AllEditableFieldsProvider.getAllEditableFields;

public class JEXLEvaluator {

    // Configuración del motor (hazlo una sola vez)
    static JexlEngine jexl = new JexlBuilder()
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
                        ex.printStackTrace();
                    }
                });
            }
        }

        // 3. Evaluar
        return e.evaluate(jc);
    }
}
