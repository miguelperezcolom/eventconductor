package io.mateu.workflow.analysis;

import io.mateu.workflow.analysis.TemplateSyntax.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateSyntaxTest {

    @Test
    void splitsLiteralsAndExpressions() {
        assertThat(TemplateSyntax.parse("Order ${orderId} for ${name}!")).containsExactly(
                new Segment(false, "Order "), new Segment(true, "orderId"), new Segment(false, " for "),
                new Segment(true, "name"), new Segment(false, "!"));
    }

    @Test
    void bracesAndQuotesInsideAnExpressionDoNotEndIt() {
        assertThat(TemplateSyntax.parse("${ {'a': '}', 'b': \"{x}\"} }")).containsExactly(
                new Segment(true, "{'a': '}', 'b': \"{x}\"}"));
        assertThat(TemplateSyntax.parse("${'it\\'s'}")).containsExactly(new Segment(true, "'it\\'s'"));
    }

    @Test
    void escapedDollarBraceIsLiteral() {
        assertThat(TemplateSyntax.parse("cost $${price} is ${price}")).containsExactly(
                new Segment(false, "cost ${price} is "), new Segment(true, "price"));
        assertThat(TemplateSyntax.singleExpression("$${x}")).isNull();
    }

    @Test
    void aSingleExpressionIsATypedLeaf() {
        assertThat(TemplateSyntax.singleExpression("${amount * 2}")).isEqualTo("amount * 2");
        assertThat(TemplateSyntax.singleExpression("  ${a}  ")).isEqualTo("a");
        assertThat(TemplateSyntax.singleExpression("x ${a}")).isNull();
        assertThat(TemplateSyntax.singleExpression("${a}${b}")).isNull();
        assertThat(TemplateSyntax.singleExpression(null)).isNull();
        assertThat(TemplateSyntax.singleExpression("plain")).isNull();
    }

    @Test
    void malformedTemplatesAreRefused() {
        assertThatThrownBy(() -> TemplateSyntax.parse("a ${unclosed")).hasMessageContaining("Unterminated");
        assertThatThrownBy(() -> TemplateSyntax.parse("${ }")).hasMessageContaining("Empty");
    }

    @Test
    void collectsEveryExpressionOfAStructuredTemplate() {
        var template = Map.of("id", "${orderId}", "items", List.of("${a}", 3, "text"),
                "nested", Map.of("note", "x ${b} y"));
        assertThat(TemplateSyntax.expressions(template, "body"))
                .extracting(Map.Entry::getValue).containsExactlyInAnyOrder("orderId", "a", "b");
        assertThat(TemplateSyntax.expressions(template, "body"))
                .extracting(Map.Entry::getKey).contains("body.items[0]", "body.nested.note");
        assertThat(TemplateSyntax.isTemplate("none")).isFalse();
    }
}
