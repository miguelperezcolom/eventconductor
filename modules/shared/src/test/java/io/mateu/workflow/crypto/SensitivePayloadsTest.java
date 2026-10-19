package io.mateu.workflow.crypto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitivePayloadsTest {

    final VariableEncryption encryption = AesGcmVariableCodecTest.encryption("k", Map.of("k", AesGcmVariableCodecTest.key()));
    final SensitiveNames names = SensitiveNames.of(List.of("cardNumber", "patient.*"));

    @Test
    void theSensitiveVariablesOfAnyEventShapeAreSealedAndOpened() {
        var json = """
                {"processId":"p1","variables":[{"name":"cardNumber","value":"4111"},{"name":"amount","value":"10"}],
                 "set":[{"name":"patient.ssn","value":"123-45"}],"nested":{"outputVariables":[{"name":"patient","value":"{}"}]}}
                """;
        var sealed = SensitivePayloads.sealVariables(json, names, encryption);
        assertThat(sealed).doesNotContain("4111").doesNotContain("123-45").contains("\"amount\"").contains("\"10\"");
        assertThat(SensitivePayloads.containsSealed(sealed)).isTrue();
        var opened = SensitivePayloads.open(sealed, encryption);
        assertThat(opened).contains("4111").contains("123-45").doesNotContain(VariableEnvelope.PREFIX);
        // Sealing what is already sealed changes nothing.
        assertThat(SensitivePayloads.sealVariables(sealed, names, encryption)).isEqualTo(sealed);
    }

    @Test
    void aMigrationsVariableMappingIsSealedByName() {
        var json = "{\"processId\":\"p\",\"variableMapping\":{\"cardNumber\":\"4111\",\"amount\":\"10\"}}";
        var sealed = SensitivePayloads.sealVariables(json, names, encryption);
        assertThat(sealed).doesNotContain("4111").contains("\"amount\":\"10\"");
        assertThat(SensitivePayloads.open(sealed, encryption)).contains("4111");
    }

    @Test
    void nothingToSealOrOpenLeavesTheTextAsItIs() {
        var json = "{\"variables\":[{\"name\":\"amount\",\"value\":\"10\"}]}";
        assertThat(SensitivePayloads.sealVariables(json, names, encryption)).isSameAs(json);
        assertThat(SensitivePayloads.sealVariables(json, SensitiveNames.NONE, encryption)).isSameAs(json);
        assertThat(SensitivePayloads.sealVariables(null, names, encryption)).isNull();
        assertThat(SensitivePayloads.open(json, encryption)).isSameAs(json);
        assertThat(SensitivePayloads.open(null, encryption)).isNull();
    }

    @Test
    void aSealedBlobOrStringDocumentOpens() {
        var blob = encryption.seal("{\"a\":1}");
        assertThat(SensitivePayloads.open(blob, encryption)).isEqualTo("{\"a\":1}");
        var stringDocument = "\"" + encryption.seal("text") + "\"";
        assertThat(SensitivePayloads.open(stringDocument, encryption)).isEqualTo("\"text\"");
        var array = "[\"" + encryption.seal("x") + "\", 1]";
        assertThat(SensitivePayloads.open(array, encryption)).isEqualTo("[\"x\",1]");
    }

    @Test
    void sensitiveNamesCompose() {
        assertThat(SensitiveNames.NONE.isEmpty()).isTrue();
        assertThat(SensitiveNames.NONE.test("x")).isFalse();
        var plus = names.plus(List.of("__http"));
        assertThat(plus.test("__http")).isTrue();
        assertThat(plus.patterns()).contains("cardNumber", "patient.*", "__http");
        assertThat(names.plus(List.of())).isSameAs(names);
        assertThat(SensitiveNames.of(List.of(" a ", "a"))).isEqualTo(SensitiveNames.of(List.of("a")));
        assertThat(SensitiveNames.of(null)).isSameAs(SensitiveNames.NONE);
        assertThat(names.toString()).contains("cardNumber");
        assertThat(names.hashCode()).isEqualTo(SensitiveNames.of(List.of("cardNumber", "patient.*")).hashCode());
    }
}
