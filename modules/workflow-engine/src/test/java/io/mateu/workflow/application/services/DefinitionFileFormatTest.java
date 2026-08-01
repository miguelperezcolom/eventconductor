package io.mateu.workflow.application.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionFileFormatTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recognisesEveryDefinitionExtension() {
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.ec")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.json")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.yaml")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("order.yml")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("ORDER.EC")).isTrue(); // case-insensitive
        assertThat(DefinitionFileFormat.isDefinitionFileName("readme.md")).isFalse();
        assertThat(DefinitionFileFormat.isDefinitionFileName(null)).isFalse();
    }

    @Test
    void picksParserByExtensionRegardlessOfContent() {
        assertThat(DefinitionFileFormat.isYaml("x.yaml", bytes("{}"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("x.yml", bytes("name: a"))).isTrue();
        assertThat(DefinitionFileFormat.isYaml("x.json", bytes("name: a"))).isFalse();
    }

    @Test
    void sniffsEcContentToChooseJsonOrYaml() {
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("  \n { \"name\": \"a\" }"))).isFalse(); // JSON object
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("[1,2]"))).isFalse();                    // JSON array
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("name: a\nsteps: []"))).isTrue();         // YAML
        assertThat(DefinitionFileFormat.isYaml("order.ec", bytes("   "))).isTrue();                        // blank → YAML default
        assertThat(DefinitionFileFormat.isYaml("order.ec", null)).isTrue();
    }
}
