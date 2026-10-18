package io.mateu.workflow.search;

import io.mateu.workflow.search.SearchAttributeFilter.Op;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    @Test
    void parsesConditionsJoinedByAnd() {
        assertThat(SearchQuery.parse("customerId = \"C-9\" AND amount > 100 and placedOn<=2026-10-18 AND region='EU west'"))
                .containsExactly(
                        new SearchAttributeFilter("customerId", Op.EQ, "C-9"),
                        new SearchAttributeFilter("amount", Op.GT, "100"),
                        new SearchAttributeFilter("placedOn", Op.LTE, "2026-10-18"),
                        new SearchAttributeFilter("region", Op.EQ, "EU west"));
        assertThat(SearchQuery.parse("a>=1 AND b<2 AND c=x")).extracting(SearchAttributeFilter::op)
                .containsExactly(Op.GTE, Op.LT, Op.EQ);
        assertThat(SearchQuery.parse(null)).isEmpty();
        assertThat(SearchQuery.parse("  ")).isEmpty();
    }

    @Test
    void quotedValuesKeepWhatWouldOtherwiseBeSyntax() {
        var filters = SearchQuery.parse("note = 'it\\'s' AND label = \"a \\\" AND b = 1\"");
        assertThat(filters).extracting(SearchAttributeFilter::value).containsExactly("it's", "a \" AND b = 1");
        assertThat(SearchQuery.parse("label = \"x' OR 1=1 --\"")).singleElement()
                .extracting(SearchAttributeFilter::value).isEqualTo("x' OR 1=1 --");
    }

    @Test
    void formatsBackToWhatItParses() {
        var filters = List.of(new SearchAttributeFilter("customerId", Op.EQ, "C-9"),
                new SearchAttributeFilter("label", Op.EQ, "two words \"quoted\""),
                new SearchAttributeFilter("amount", Op.GTE, "100.5"));
        var text = SearchQuery.format(filters);
        assertThat(text).isEqualTo("customerId = C-9 AND label = \"two words \\\"quoted\\\"\" AND amount >= 100.5");
        assertThat(SearchQuery.parse(text)).isEqualTo(filters);
        assertThat(SearchQuery.format(List.of())).isEmpty();
        assertThat(SearchQuery.format(null)).isEmpty();
    }

    @Test
    void refusesWhatIsNotAQuery() {
        assertThatThrownBy(() -> SearchQuery.parse("= 3")).hasMessageContaining("attribute name");
        assertThatThrownBy(() -> SearchQuery.parse("a 3")).hasMessageContaining("operator");
        assertThatThrownBy(() -> SearchQuery.parse("a =")).hasMessageContaining("a value");
        assertThatThrownBy(() -> SearchQuery.parse("a = 1 OR b = 2")).hasMessageContaining("'AND'");
        assertThatThrownBy(() -> SearchQuery.parse("a = 1 ANDb = 2")).hasMessageContaining("'AND'");
        assertThatThrownBy(() -> SearchQuery.parse("a = \"open")).hasMessageContaining("Unterminated");
        assertThatThrownBy(() -> SearchQuery.parse("a > abc")).hasMessageContaining("numbers and dates");
        assertThatThrownBy(() -> SearchQuery.parse("a = " + "x".repeat(SearchQuery.MAX_LENGTH)))
                .hasMessageContaining("longer than");
        var many = new StringBuilder("a0 = 1");
        for (int i = 1; i <= SearchAttributeFilter.MAX_FILTERS; i++) {
            many.append(" AND a").append(i).append(" = 1");
        }
        assertThatThrownBy(() -> SearchQuery.parse(many.toString())).hasMessageContaining("at most");
    }
}
