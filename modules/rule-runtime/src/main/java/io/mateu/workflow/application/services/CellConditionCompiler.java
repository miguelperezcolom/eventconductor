package io.mateu.workflow.application.services;

import java.util.List;

/**
 * Compiles a decision-table cell into a JEXL condition over its input column.
 * <ul>
 *   <li>{@code *} or blank → always true (returns null: no condition)</li>
 *   <li>{@code > >= < <= != ==} prefix → {@code <input> <cell>}</li>
 *   <li>numeric literal → numeric equality</li>
 *   <li>quoted literal → equality as written</li>
 *   <li>anything else → string equality</li>
 * </ul>
 */
public class CellConditionCompiler {

    private static final List<String> OPERATORS = List.of(">=", "<=", "!=", "==", ">", "<");

    /** Returns the JEXL condition for the cell, or null if the cell always matches. */
    public String compile(String input, String cell) {
        if (cell == null) {
            return null;
        }
        var trimmed = cell.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return null;
        }
        for (var operator : OPERATORS) {
            if (trimmed.startsWith(operator)) {
                return input + " " + operator + " " + trimmed.substring(operator.length()).trim();
            }
        }
        if (isNumeric(trimmed) || isQuoted(trimmed)) {
            return input + " == " + trimmed;
        }
        return input + " == '" + trimmed.replace("'", "\\'") + "'";
    }

    private boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isQuoted(String value) {
        return value.length() >= 2 && value.startsWith("'") && value.endsWith("'");
    }
}
