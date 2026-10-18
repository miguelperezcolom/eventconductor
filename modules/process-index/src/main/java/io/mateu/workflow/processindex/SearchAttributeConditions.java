package io.mateu.workflow.processindex;

import io.mateu.workflow.search.SearchAttributeFilter;
import io.mateu.workflow.search.SearchAttributeFilter.Op;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search-attribute conditions as query text — one {@code EXISTS} per condition over an attribute table
 * keyed by process id — for every store that keeps attributes in rows: the engine's
 * {@code process_search_attribute} (JPQL), the embedded process index (JPQL) and the remote index
 * (plain SQL).
 *
 * <p>Only the shape is written into the text; the attribute name and every value are bound parameters.
 * A condition matches whichever stored row of that name it can compare with — see
 * {@link SearchAttributeFilter} — so it does not need to know the attribute's declared type:
 *
 * <pre>
 *   EXISTS (SELECT 1 FROM attrs a WHERE a.process = p.id AND a.name = ?
 *           AND ((a.type IN ('keyword','bool') AND a.keyword = ?)
 *             OR (a.type = 'text' AND LOWER(a.keyword) LIKE ? ESCAPE '!')
 *             OR (a.type = 'number' AND a.number = ?)          -- when the value reads as a number
 *             OR (a.type = 'date' AND a.date = ?)))            -- when it reads as a date
 * </pre>
 *
 * Each {@code (name, value)} probe is answered by the table's {@code (name, keyword|number|date)} indexes.
 */
public final class SearchAttributeConditions {

    /** Where the columns are and how parameters are written. */
    public enum Dialect {
        /** JPQL over an attribute entity: fields {@code attrName}, {@code attrType}, {@code keywordValue}, … and {@code :named} parameters. */
        JPQL("processId", "attrName", "attrType", "keywordValue", "numberValue", "dateValue"),
        /** SQL over an attribute table: columns {@code attr_name}, {@code attr_type}, {@code keyword_value}, … and {@code ?}. */
        SQL("process_id", "attr_name", "attr_type", "keyword_value", "number_value", "date_value");

        final String processId;
        final String name;
        final String type;
        final String keyword;
        final String number;
        final String date;

        Dialect(String processId, String name, String type, String keyword, String number, String date) {
            this.processId = processId;
            this.name = name;
            this.type = type;
            this.keyword = keyword;
            this.number = number;
            this.date = date;
        }
    }

    /**
     * Query text and its parameters: {@link #named} for JPQL (by name), {@link #positional} for SQL (in
     * order).
     */
    public record Fragment(String text, Map<String, Object> named, List<Object> positional) {
        public boolean isEmpty() {
            return text.isEmpty();
        }
    }

    private SearchAttributeConditions() {
    }

    /**
     * {@code AND EXISTS (…)} per condition, or empty text for none.
     *
     * @param from      the attribute entity (JPQL) or table (SQL)
     * @param outerId   the outer query's process id, e.g. {@code p.id}
     * @param prefix    parameter-name prefix for JPQL, so two fragments in one query do not collide
     */
    public static Fragment of(Dialect dialect, String from, String outerId, List<SearchAttributeFilter> filters,
                              String prefix) {
        var text = new StringBuilder();
        var named = new LinkedHashMap<String, Object>();
        var positional = new ArrayList<>();
        if (filters == null) {
            return new Fragment("", named, positional);
        }
        for (int i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            var alias = "sa" + i;
            var binder = new Binder(dialect, prefix + i, named, positional);
            text.append(" and exists (select 1 from ").append(from).append(' ').append(alias)
                    .append(" where ").append(alias).append('.').append(dialect.processId).append(" = ").append(outerId)
                    .append(" and ").append(alias).append('.').append(dialect.name).append(" = ").append(binder.bind("n", filter.name()))
                    .append(" and (");
            var alternatives = new ArrayList<String>();
            var col = alias + ".";
            if (filter.op() == Op.EQ) {
                alternatives.add("(" + col + dialect.type + " in ('keyword', 'bool') and " + col + dialect.keyword
                        + " = " + binder.bind("k", filter.value()) + ")");
                alternatives.add("(" + col + dialect.type + " = 'text' and lower(" + col + dialect.keyword + ") like "
                        + binder.bind("t", "%" + escapeLike(filter.textPattern()) + "%") + " escape '!')");
            }
            var number = filter.number();
            if (number.isPresent()) {
                alternatives.add("(" + col + dialect.type + " = 'number' and " + col + dialect.number + " "
                        + filter.op().symbol() + " " + binder.bind("x", number.get()) + ")");
            }
            var date = filter.date();
            if (date.isPresent()) {
                alternatives.add("(" + col + dialect.type + " = 'date' and " + col + dialect.date + " "
                        + filter.op().symbol() + " " + binder.bind("d", date.get()) + ")");
            }
            text.append(String.join(" or ", alternatives)).append("))");
        }
        return new Fragment(text.toString(), named, positional);
    }

    /** A LIKE pattern's own wildcards, escaped with {@code !} — portable, unlike a backslash. */
    static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record Binder(Dialect dialect, String suffix, Map<String, Object> named, List<Object> positional) {
        String bind(String kind, Object value) {
            if (dialect == Dialect.SQL) {
                positional.add(value);
                return "?";
            }
            var name = kind + suffix;
            named.put(name, value);
            return ":" + name;
        }
    }
}
