package io.mateu.workflow.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableEncryptionAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VariableEncryptionAutoConfiguration.class));

    @Test
    void withoutKeysEncryptionIsOff() {
        runner.run(context -> assertThat(context.getBean(VariableEncryption.class).enabled()).isFalse());
    }

    @Test
    void keysFromPropertiesTurnItOn() {
        var key = Base64.getEncoder().encodeToString(AesGcmVariableCodecTest.key());
        runner.withPropertyValues("workflow.encryption.keys.k1=" + key, "workflow.encryption.active-key=k1")
                .run(context -> {
                    var encryption = context.getBean(VariableEncryption.class);
                    assertThat(encryption.enabled()).isTrue();
                    assertThat(encryption.open(encryption.seal("v"))).isEqualTo("v");
                });
    }

    @Test
    void aBadKeyFailsStartup() {
        runner.withPropertyValues("workflow.encryption.keys.k1=" + Base64.getEncoder().encodeToString(new byte[8]))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aKeySourceOrACodecBeanWins() {
        runner.withBean(EncryptionKeySource.class, () -> EncryptionKeySource.of("vault", Map.of("vault", AesGcmVariableCodecTest.key())))
                .run(context -> assertThat(context.getBean(VariableEncryption.class).activeKeyId()).isEqualTo("vault"));
        var codec = new AesGcmVariableCodec("kms", Map.of("kms", AesGcmVariableCodecTest.key()));
        runner.withBean(VariableCodec.class, () -> codec)
                .run(context -> assertThat(context.getBean(VariableEncryption.class).activeKeyId()).isEqualTo("kms"));
    }
}
