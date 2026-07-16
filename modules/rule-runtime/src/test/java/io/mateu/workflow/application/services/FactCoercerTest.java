package io.mateu.workflow.application.services;

import io.mateu.workflow.dtos.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactCoercerTest {

    private final FactCoercer coercer = new FactCoercer();

    @Test
    void coercesNumbersBooleansAndStrings() {
        assertThat(coercer.coerce("42")).isEqualTo(42L);
        assertThat(coercer.coerce("42.5")).isEqualTo(42.5);
        assertThat(coercer.coerce("true")).isEqualTo(true);
        assertThat(coercer.coerce("VIP")).isEqualTo("VIP");
        assertThat(coercer.coerce(null)).isNull();
    }

    @Test
    void coercesJsonPayloads() {
        assertThat(coercer.coerce("{\"total\": 200}")).isEqualTo(Map.of("total", 200));
        assertThat(coercer.coerce("[1, 2]")).isEqualTo(List.of(1, 2));
        assertThat(coercer.coerce("{not json")).isEqualTo("{not json");
    }

    @Test
    void dottedNamesBecomeNestedMaps() {
        var facts = coercer.toFacts(List.of(
                new Variable("order.total", "200"),
                new Variable("order.currency", "EUR"),
                new Variable("vip", "true")));

        assertThat(facts).containsEntry("order", Map.of("total", 200L, "currency", "EUR"));
        assertThat(facts).containsEntry("vip", true);
    }

    @Test
    void plainNameOverriddenByNestedNameStillWorks() {
        var facts = coercer.toFacts(List.of(
                new Variable("order", "plain"),
                new Variable("order.total", "5")));

        assertThat(facts).containsEntry("order", Map.of("total", 5L));
    }
}
