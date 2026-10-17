package io.mateu.workflow.application.services;

import java.util.List;

/**
 * File-format rules for workflow definition files. Besides the plain {@code .json} / {@code .yaml}
 * / {@code .yml} forms, EventConductor has a first-class {@code .ec} extension whose content is
 * either JSON or YAML — decided by sniffing, since the extension doesn't say which.
 *
 * <p>A definition can also say what it is in a suffix that editors already know as YAML:
 * {@code order.workflow.yaml} or {@code order.ec.yaml} (and their {@code .yml} twins). Those are
 * plain {@code .yaml} files as far as parsing and scanning go — they need no rule of their own here
 * — but the compound suffix is not part of the definition's name: a file that declares no id gets
 * {@code order} from either, not {@code order.workflow} (see {@code DerivedIds}).
 */
public final class DefinitionFileFormat {

    /** The first-class EventConductor definition extension. */
    public static final String EC_EXTENSION = ".ec";

    /**
     * The compound suffixes that mark a YAML file as a workflow definition. They are what the IDE
     * plugins associate with the workflow schema and graph editor besides {@code .ec}.
     */
    public static final List<String> YAML_WORKFLOW_SUFFIXES =
            List.of(".workflow.yaml", ".workflow.yml", ".ec.yaml", ".ec.yml");

    /**
     * The classpath patterns definitions are loaded from, relative to {@code classpath:/workflows/}.
     * They do not overlap — {@code *.yaml}/{@code *.yml} already cover every compound suffix — so no
     * file is loaded twice.
     *
     * <p>One pattern per extension, never {@code *.{yaml,yml}}: Spring's {@code AntPathMatcher} has no
     * brace alternation, and reads {@code {yaml,yml}} as a URI-template variable that matches anything
     * — so that pattern used to match every file with a dot in its name, loading each {@code .json}
     * and {@code .ec} a second time (through the YAML parser) and trying stray files like a README.
     */
    public static final List<String> CLASSPATH_PATTERNS = List.of("*.json", "*.yaml", "*.yml", "*" + EC_EXTENSION);

    private DefinitionFileFormat() {}

    /**
     * Whether a file name is a recognised workflow-definition file (.ec, .json, .yaml, .yml — which
     * includes the compound {@code .workflow.yaml} / {@code .ec.yaml} forms). A schedule file
     * ({@code cron.yaml} / {@code cron.yml}, see {@code ScheduleRules}) and an event-trigger file
     * ({@code events.yaml} / {@code events.yml}, see {@code EventTriggerRules}) never are, even though
     * their extension says YAML.
     */
    public static boolean isDefinitionFileName(String name) {
        if (name == null) return false;
        if (io.mateu.workflow.analysis.ScheduleRules.isCronFileName(name)) return false;
        if (io.mateu.workflow.analysis.EventTriggerRules.isEventFileName(name)) return false;
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
