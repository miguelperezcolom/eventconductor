package io.mateu.workflow.search;

import io.mateu.workflow.analysis.SearchAttributeRules;
import io.mateu.workflow.search.SearchAttributeFilter.Op;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchAttributeFilterTest {

    static SearchAttributeValue value(String name, SearchAttributeType type, Object raw) {
        return SearchAttributeValue.of(name, type, raw).orElseThrow();
    }

    @Test
    void valuesAreCoercedToTheirTypeOrNotIndexed() {
        assertThat(value("k", SearchAttributeType.KEYWORD, " C-9 ").keyword()).isEqualTo("C-9");
        assertThat(value("n", SearchAttributeType.NUMBER, "12.50").number()).isEqualTo(12.5);
        assertThat(value("n", SearchAttributeType.NUMBER, 7).number()).isEqualTo(7.0);
        assertThat(value("b", SearchAttributeType.BOOL, "TRUE").keyword()).isEqualTo("true");
        assertThat(value("d", SearchAttributeType.DATE, "2026-10-18").date()).isEqualTo(LocalDate.of(2026, 10, 18).atStartOfDay());
        assertThat(value("d", SearchAttributeType.DATE, "2026-10-18T09:30:00").date()).isEqualTo(LocalDateTime.of(2026, 10, 18, 9, 30));
        assertThat(value("d", SearchAttributeType.DATE, "2026-10-18T09:30:00Z").date())
                .isEqualTo(LocalDateTime.ofInstant(java.time.Instant.parse("2026-10-18T09:30:00Z"), ZoneId.systemDefault()));
        assertThat(value("d", SearchAttributeType.DATE, "2026-10-18T09:30:00+02:00").date()).isNotNull();
        assertThat(value("t", SearchAttributeType.TEXT, "x".repeat(400)).keyword()).hasSize(SearchAttributeRules.MAX_VALUE_LENGTH);

        assertThat(SearchAttributeValue.of("n", SearchAttributeType.NUMBER, "12a")).isEmpty();
        assertThat(SearchAttributeValue.of("n", SearchAttributeType.NUMBER, Double.NaN)).isEmpty();
        assertThat(SearchAttributeValue.of("b", SearchAttributeType.BOOL, "yes")).isEmpty();
        assertThat(SearchAttributeValue.of("d", SearchAttributeType.DATE, "yesterday")).isEmpty();
        assertThat(SearchAttributeValue.of("k", SearchAttributeType.KEYWORD, null)).isEmpty();
        assertThat(SearchAttributeValue.of("k", SearchAttributeType.KEYWORD, "  ")).isEmpty();
        assertThat(SearchAttributeValue.of("k", SearchAttributeType.KEYWORD, "null")).isEmpty();
    }

    @Test
    void displayWritesEachTypeAsText() {
        assertThat(value("n", SearchAttributeType.NUMBER, "100.0").display()).isEqualTo("100");
        assertThat(value("d", SearchAttributeType.DATE, "2026-10-18").display()).isEqualTo("2026-10-18T00:00");
        assertThat(value("k", SearchAttributeType.KEYWORD, "x").display()).isEqualTo("x");
    }

    @Test
    void matchesByTheStoredType() {
        var keyword = value("customerId", SearchAttributeType.KEYWORD, "C-9");
        var text = value("note", SearchAttributeType.TEXT, "Urgent delivery to Palma");
        var number = value("amount", SearchAttributeType.NUMBER, "150");
        var date = value("placedOn", SearchAttributeType.DATE, "2026-10-18T10:00:00");
        var bool = value("vip", SearchAttributeType.BOOL, "true");

        assertThat(new SearchAttributeFilter("customerId", Op.EQ, "C-9").matches(keyword)).isTrue();
        assertThat(new SearchAttributeFilter("customerId", Op.EQ, "c-9").matches(keyword)).isFalse();
        assertThat(new SearchAttributeFilter("note", Op.EQ, "PALMA").matches(text)).isTrue();
        assertThat(new SearchAttributeFilter("note", Op.EQ, "madrid").matches(text)).isFalse();
        assertThat(new SearchAttributeFilter("amount", Op.EQ, "150.0").matches(number)).isTrue();
        assertThat(new SearchAttributeFilter("amount", Op.GT, "100").matches(number)).isTrue();
        assertThat(new SearchAttributeFilter("amount", Op.GTE, "150").matches(number)).isTrue();
        assertThat(new SearchAttributeFilter("amount", Op.LT, "150").matches(number)).isFalse();
        assertThat(new SearchAttributeFilter("amount", Op.LTE, "150").matches(number)).isTrue();
        assertThat(new SearchAttributeFilter("amount", Op.EQ, "abc").matches(number)).isFalse();
        assertThat(new SearchAttributeFilter("placedOn", Op.LTE, "2026-10-18").matches(date)).isTrue();
        assertThat(new SearchAttributeFilter("placedOn", Op.GT, "2026-10-18").matches(date)).isFalse();
        assertThat(new SearchAttributeFilter("placedOn", Op.GTE, "2026-10-18").matches(date)).isTrue();
        assertThat(new SearchAttributeFilter("placedOn", Op.EQ, "2026-10-18T10:00:00").matches(date)).isTrue();
        assertThat(new SearchAttributeFilter("vip", Op.EQ, "true").matches(bool)).isTrue();
        assertThat(new SearchAttributeFilter("other", Op.EQ, "C-9").matches(keyword)).isFalse();
        assertThat(new SearchAttributeFilter("customerId", Op.EQ, "C-9").matches(null)).isFalse();

        var all = List.of(keyword, number);
        assertThat(SearchAttributeFilter.matchAll(List.of(new SearchAttributeFilter("customerId", Op.EQ, "C-9"),
                new SearchAttributeFilter("amount", Op.GT, "100")), all)).isTrue();
        assertThat(SearchAttributeFilter.matchAll(List.of(new SearchAttributeFilter("customerId", Op.EQ, "C-9"),
                new SearchAttributeFilter("amount", Op.GT, "200")), all)).isFalse();
        assertThat(SearchAttributeFilter.matchAll(List.of(), null)).isTrue();
        assertThat(SearchAttributeFilter.matchAll(List.of(new SearchAttributeFilter("a", Op.EQ, "1")), null)).isFalse();
    }

    @Test
    void refusesWhatCannotBeCompared() {
        assertThatThrownBy(() -> new SearchAttributeFilter("bad name", Op.EQ, "x")).hasMessageContaining("not a search attribute name");
        assertThatThrownBy(() -> new SearchAttributeFilter("a", Op.EQ, " ")).hasMessageContaining("needs a value");
        assertThatThrownBy(() -> new SearchAttributeFilter("a", Op.EQ, "x".repeat(300))).hasMessageContaining("longer than");
        assertThatThrownBy(() -> new SearchAttributeFilter("a", Op.GT, "abc")).hasMessageContaining("numbers and dates");
        assertThat(new SearchAttributeFilter("a", null, "x").op()).isEqualTo(Op.EQ);
        assertThat(Op.ofSuffix(null)).isEqualTo(Op.EQ);
        assertThat(Op.ofSuffix("GTE")).isEqualTo(Op.GTE);
        assertThat(Op.ofSuffix("eq")).isEqualTo(Op.EQ);
        assertThat(Op.ofSuffix("lt")).isEqualTo(Op.LT);
        assertThat(Op.ofSuffix("lte")).isEqualTo(Op.LTE);
        assertThat(Op.ofSuffix("gt")).isEqualTo(Op.GT);
        assertThatThrownBy(() -> Op.ofSuffix("like")).hasMessageContaining("Unknown attribute operator");
        assertThat(SearchAttributeFilter.checked(null)).isEmpty();
    }

    @Test
    void typesReadAsTheFileWritesThem() {
        assertThat(SearchAttributeType.of(null)).isEqualTo(SearchAttributeType.KEYWORD);
        assertThat(SearchAttributeType.of(" Number ")).isEqualTo(SearchAttributeType.NUMBER);
        assertThat(SearchAttributeType.DATE.keyword()).isEqualTo("date");
        assertThat(SearchAttributeType.BOOL.isTextual()).isTrue();
        assertThat(SearchAttributeType.NUMBER.isTextual()).isFalse();
        assertThat(SearchAttributeValue.sameValues(List.of(value("a", SearchAttributeType.KEYWORD, "1"),
                value("b", SearchAttributeType.KEYWORD, "2")), List.of(value("b", SearchAttributeType.KEYWORD, "2"),
                value("a", SearchAttributeType.KEYWORD, "1")))).isTrue();
        assertThat(SearchAttributeValue.sameValues(null, List.of())).isTrue();
    }
}
