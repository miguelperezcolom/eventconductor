package io.mateu.workflow.codegen;

import java.util.Set;

/**
 * Turns the free-form names in a contract (id, group, error code, attribute name) into the Java
 * names the generated code uses. The rules are deliberately boring and total: any input yields a
 * legal Java identifier, and a name that is already one is left untouched, so a well-behaved
 * contract reads exactly as written.
 */
final class Names {

    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "var", "record", "yield");

    private Names() {
    }

    /** {@code confirm-booking} → {@code ConfirmBooking}; {@code SOLD_OUT} → {@code SoldOut}. */
    static String pascal(String raw) {
        var out = new StringBuilder();
        for (var part : raw.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            var allUpper = part.equals(part.toUpperCase());
            out.append(Character.toUpperCase(part.charAt(0)));
            out.append(allUpper ? part.substring(1).toLowerCase() : part.substring(1));
        }
        return legal(out.toString(), "Task");
    }

    /** {@code place-order} → {@code placeOrder}. */
    static String camel(String raw) {
        var pascal = pascal(raw);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /** A contract's group as a dotted, lowercase, legal package suffix: {@code Booking Ops} → {@code booking.ops}. */
    static String groupPackage(String group) {
        var segments = new StringBuilder();
        for (var part : group.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (segments.length() > 0) {
                segments.append('.');
            }
            var lower = part.toLowerCase();
            if (Character.isDigit(lower.charAt(0)) || RESERVED.contains(lower)) {
                segments.append('_');
            }
            segments.append(lower);
        }
        return segments.length() == 0 ? "_" : segments.toString();
    }

    /**
     * A legal identifier for a record component, keeping an already-legal name as-is so binding by
     * name needs no {@code @JsonProperty}. Returns the same string when the attribute name is
     * already a fine Java field name.
     */
    static String fieldIdentifier(String attributeName) {
        if (isLegalIdentifier(attributeName)) {
            return attributeName;
        }
        var out = new StringBuilder();
        var upperNext = false;
        for (var i = 0; i < attributeName.length(); i++) {
            var c = attributeName.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else {
                upperNext = out.length() > 0;
            }
        }
        return legal(out.toString(), "value").transform(s ->
                Character.toLowerCase(s.charAt(0)) + s.substring(1));
    }

    static boolean isLegalIdentifier(String s) {
        if (s == null || s.isEmpty() || RESERVED.contains(s)
                || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (var i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String legal(String candidate, String fallback) {
        if (candidate.isEmpty()) {
            return fallback;
        }
        if (Character.isJavaIdentifierStart(candidate.charAt(0)) && !RESERVED.contains(candidate)) {
            return candidate;
        }
        return "_" + candidate;
    }
}
