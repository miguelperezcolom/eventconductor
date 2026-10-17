package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules of a definition's {@code tags} — shared as code by the engine and the Maven plugin, so a
 * tag the build accepts is one the engine lists and filters by. A tag is a short label for finding and
 * grouping definitions, and the processes of those definitions, in the console: {@code billing},
 * {@code team:payments}, {@code eu}. Metadata, not behaviour — changing tags mints no definition version.
 *
 * <ul>
 *   <li>Letters, digits, {@code .}, {@code _}, {@code :}, {@code /} and {@code -}, starting with a letter
 *       or digit, at most {@value #MAX_LENGTH} characters.</li>
 *   <li>Each tag once per definition (case-insensitively).</li>
 * </ul>
 */
public final class TagRules {

    /** The longest tag accepted. */
    public static final int MAX_LENGTH = 50;

    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private TagRules() {
    }

    /** Whether this is a tag. */
    public static boolean isValidTag(String tag) {
        return tag != null && !tag.isBlank() && tag.trim().length() <= MAX_LENGTH && TAG.matcher(tag.trim()).matches();
    }

    /** What is wrong with a definition's tags, each message prefixed with {@code where}; empty means valid. */
    public static List<String> violations(List<String> tags, String where) {
        var violations = new ArrayList<String>();
        if (tags == null) {
            return violations;
        }
        var prefix = where == null || where.isBlank() ? "" : where + ": ";
        var seen = new HashSet<String>();
        for (int i = 0; i < tags.size(); i++) {
            var tag = tags.get(i);
            if (!isValidTag(tag)) {
                violations.add(prefix + "'" + tag + "' is not a valid tag (letters, digits, '.', '_', ':', '/' and '-',"
                        + " starting with a letter or digit, at most " + MAX_LENGTH + " characters)");
            } else if (!seen.add(tag.trim().toLowerCase(java.util.Locale.ROOT))) {
                violations.add(prefix + "tag '" + tag.trim() + "' is declared more than once");
            }
        }
        return violations;
    }
}
