package io.mateu.workflow.application.services;

/**
 * File-format rules for workflow definition files. Besides the plain {@code .json} / {@code .yaml}
 * / {@code .yml} forms, EventConductor has a first-class {@code .ec} extension whose content is
 * either JSON or YAML — decided by sniffing, since the extension doesn't say which.
 */
public final class DefinitionFileFormat {

    /** The first-class EventConductor definition extension. */
    public static final String EC_EXTENSION = ".ec";

    private DefinitionFileFormat() {}

    /** Whether a file name is a recognised workflow-definition file (.ec, .json, .yaml, .yml). */
    public static boolean isDefinitionFileName(String name) {
        if (name == null) return false;
        var n = name.toLowerCase();
        return n.endsWith(EC_EXTENSION) || n.endsWith(".json") || n.endsWith(".yaml") || n.endsWith(".yml");
    }

    /**
     * Whether the file's content should be parsed as YAML rather than JSON. {@code .yaml}/{@code .yml}
     * are always YAML and {@code .json} always JSON; a {@code .ec} (or unknown) file is decided by its
     * first non-whitespace byte — {@code '{'} or {@code '['} means JSON, anything else YAML (JSON is a
     * subset of YAML anyway, so this only picks the stricter parser when it clearly applies).
     */
    public static boolean isYaml(String name, byte[] content) {
        var n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".yaml") || n.endsWith(".yml")) return true;
        if (n.endsWith(".json")) return false;
        if (content != null) {
            for (byte b : content) {
                if (Character.isWhitespace(b)) continue;
                return !(b == '{' || b == '[');
            }
        }
        return true; // empty / unreadable → default to YAML; validation rejects it downstream anyway
    }
}
