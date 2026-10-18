package io.mateu.workflow.processindex;

import io.mateu.workflow.search.SearchAttributeFilter;
import io.mateu.workflow.search.SearchAttributeFilter.Op;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shape of the conditions — and that every value travels as a parameter, never as text. */
class SearchAttributeConditionsTest {

    @Test
    void noConditionsIsNoText() {
        var none = SearchAttributeConditions.of(SearchAttributeConditions.Dialect.SQL, "t", "p.id", null, "a");
        assertThat(none.isEmpty()).isTrue();
        assertThat(SearchAttributeConditions.of(SearchAttributeConditions.Dialect.JPQL, "E", "p.id", List.of(), "a")
                .text()).isEmpty();
    }

    @Test
    void anEqualityProbesTheKeywordTheTextAndWhateverTheValueReadsAs() {
        var sql = SearchAttributeConditions.of(SearchAttributeConditions.Dialect.SQL, "attrs", "p.process_id",
                List.of(new SearchAttributeFilter("amount", Op.EQ, "100")), "a");
        assertThat(sql.text())
                .startsWith(" and exists (select 1 from attrs sa0 where sa0.process_id = p.process_id and sa0.attr_name = ?")
                .contains("sa0.attr_type in ('keyword', 'bool') and sa0.keyword_value = ?")
                .contains("lower(sa0.keyword_value) like ? escape '!'")
                .contains("sa0.attr_type = 'number' and sa0.number_value = ?")
                .doesNotContain("date_value");
        assertThat(sql.positional()).containsExactly("amount", "100", "%100%", 100.0);
    }

    @Test
    void aRangeComparesOnlyNumbersAndDates() {
        var jpql = SearchAttributeConditions.of(SearchAttributeConditions.Dialect.JPQL, "Attr", "p.id",
                List.of(new SearchAttributeFilter("placedOn", Op.GTE, "2026-10-01"),
                        new SearchAttributeFilter("customerId", Op.EQ, "C-9'; drop table x; --")), "a");
        assertThat(jpql.text())
                .contains("sa0.attrType = 'date' and sa0.dateValue >= :da0")
                .doesNotContain("keywordValue = :ka0")
                .contains("sa1.keywordValue = :ka1")
                .doesNotContain("drop table");
        assertThat(jpql.named()).containsEntry("na0", "placedOn")
                .containsEntry("da0", LocalDateTime.of(2026, 10, 1, 0, 0))
                .containsEntry("ka1", "C-9'; drop table x; --");
    }

    @Test
    void likeWildcardsInAValueAreEscaped() {
        assertThat(SearchAttributeConditions.escapeLike("50%_off!")).isEqualTo("50!%!_off!!");
        var sql = SearchAttributeConditions.of(SearchAttributeConditions.Dialect.SQL, "attrs", "p.id",
                List.of(new SearchAttributeFilter("note", Op.EQ, "100%")), "a");
        assertThat(sql.positional()).contains("%100!%%");
    }
}
