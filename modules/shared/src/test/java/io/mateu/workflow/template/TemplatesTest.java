package io.mateu.workflow.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplatesTest {

    private static final Map<String, Object> CONTEXT = Map.of(
            "orderId", "O-1", "amount", "100", "name", "Ada",
            "items", "[{\"sku\":\"A\"},{\"sku\":\"B\"}]", "tier", "gold", "flag", true);

    @Test
    void structuredLeavesKeepTheirTypeAndStringsInterpolate() {
        var rendered = Templates.render(Map.of(
                "id", "${orderId}",
                "total", "${amount * 1.5}",
                "items", "${items}",
                "note", "Order ${orderId} for ${name}",
                "active", "${flag}",
                "fixed", 7,
                "list", List.of("${name}", "literal")), CONTEXT);
        assertThat(rendered).isInstanceOf(Map.class);
        var map = (Map<?, ?>) rendered;
        assertThat(map.get("id")).isEqualTo("O-1");
        assertThat(map.get("total")).isEqualTo(150.0);
        assertThat(map.get("items")).isEqualTo(List.of(Map.of("sku", "A"), Map.of("sku", "B")));
        assertThat(map.get("note")).isEqualTo("Order O-1 for Ada");
        assertThat(map.get("active")).isEqualTo(true);
        assertThat(map.get("fixed")).isEqualTo(7);
        assertThat(map.get("list")).isEqualTo(List.of("Ada", "literal"));
    }

    @Test
    void renderJsonIsAlwaysValidJson() {
        assertThat(Templates.renderJson(Map.of("q", "${'He said \"hi\"'}"), CONTEXT))
                .isEqualTo("{\"q\":\"He said \\\"hi\\\"\"}");
    }

    @Test
    void textTemplatesInterpolate() {
        assertThat(Templates.renderText("<o id=\"${orderId}\" t=\"${tier ?: 'std'}\" n=\"${missing}\"/>", CONTEXT))
                .isEqualTo("<o id=\"O-1\" t=\"gold\" n=\"\"/>");
        assertThat(Templates.renderText("no template", CONTEXT)).isEqualTo("no template");
        assertThat(Templates.renderText("list ${items}", CONTEXT)).contains("sku");
        assertThat(Templates.renderText("map ${ {'a': 1} }", CONTEXT)).isEqualTo("map {\"a\":1}");
        assertThat(Templates.renderText(null, CONTEXT)).isNull();
    }

    @Test
    void aJsonLookingStringThatIsNotJsonStaysAString() {
        assertThat(Templates.render("${v}", Map.of("v", "{not json}"))).isEqualTo("{not json}");
    }

    @Test
    void theSandboxHolds() {
        // RESTRICTED permissions: reflection is not reachable. Non-strict, the forbidden call reads as
        // null (as it does in every guard) rather than handing anything back.
        assertThat(Templates.render("${''.getClass().forName('java.lang.Runtime')}", CONTEXT)).isNull();
        assertThat(Templates.renderText("x${''.getClass().forName('java.lang.Runtime').getRuntime()}", CONTEXT)).isEqualTo("x");
        assertThat(Templates.problems(Map.of("a", "${x = 1}"), "body")).isNotEmpty();
    }

    @Test
    void problemsReportUnparseableExpressionsWithWhereTheyAre() {
        assertThat(Templates.problems(Map.of("ok", "${a + 1}", "bad", List.of("${a +}")), "step 'x' body"))
                .singleElement().asString().contains("step 'x' body.bad[0]");
        assertThat(Templates.problems("${unclosed", "t")).singleElement().asString().contains("Unterminated");
        assertThat(Templates.problems(Map.of("n", 3), "t")).isEmpty();
    }

    @Test
    void anExpressionThatFailsIsATemplateException() {
        assertThatThrownBy(() -> Templates.renderText("${a +}", CONTEXT)).isInstanceOf(Templates.TemplateException.class);
        assertThatThrownBy(() -> Templates.renderText("${unclosed", CONTEXT)).isInstanceOf(Templates.TemplateException.class);
    }
}
