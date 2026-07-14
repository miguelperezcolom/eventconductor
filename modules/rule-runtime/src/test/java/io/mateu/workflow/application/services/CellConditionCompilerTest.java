package io.mateu.workflow.application.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CellConditionCompilerTest {

    private final CellConditionCompiler compiler = new CellConditionCompiler();

    @Test
    void wildcardAndBlankCellsMatchAlways() {
        assertThat(compiler.compile("order.total", "*")).isNull();
        assertThat(compiler.compile("order.total", "  ")).isNull();
        assertThat(compiler.compile("order.total", null)).isNull();
    }

    @Test
    void operatorCellsKeepTheOperator() {
        assertThat(compiler.compile("order.total", "> 100")).isEqualTo("order.total > 100");
        assertThat(compiler.compile("order.total", ">=100")).isEqualTo("order.total >= 100");
        assertThat(compiler.compile("order.total", "!= 5")).isEqualTo("order.total != 5");
        assertThat(compiler.compile("order.total", "<= 10")).isEqualTo("order.total <= 10");
    }

    @Test
    void numericCellsCompileToNumericEquality() {
        assertThat(compiler.compile("order.total", "100")).isEqualTo("order.total == 100");
        assertThat(compiler.compile("order.total", "99.5")).isEqualTo("order.total == 99.5");
    }

    @Test
    void quotedCellsAreKeptAsWritten() {
        assertThat(compiler.compile("customer.category", "'VIP'")).isEqualTo("customer.category == 'VIP'");
    }

    @Test
    void plainLiteralsCompileToStringEquality() {
        assertThat(compiler.compile("customer.category", "VIP")).isEqualTo("customer.category == 'VIP'");
        assertThat(compiler.compile("customer.name", "O'Brien")).isEqualTo("customer.name == 'O\\'Brien'");
    }
}
