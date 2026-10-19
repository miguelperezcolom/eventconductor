package io.mateu.workflow.crypto;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The {@link VariableEncryption} every module shares: built from a {@link VariableCodec} bean if the
 * application declares one, else from an {@link EncryptionKeySource} bean, else from
 * {@code workflow.encryption.keys.*} — and {@link VariableEncryption#DISABLED} when none of them is there.
 *
 * <p>A key that cannot be used (not base64, not 32 bytes, an unknown active key) fails startup here,
 * with the key's id and never its value.
 */
@AutoConfiguration
@EnableConfigurationProperties(EncryptionProperties.class)
public class VariableEncryptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VariableEncryption.class)
    public VariableEncryption variableEncryption(EncryptionProperties properties,
                                                 ObjectProvider<VariableCodec> codec,
                                                 ObjectProvider<EncryptionKeySource> keySource) {
        var declared = codec.getIfAvailable();
        if (declared != null) {
            return new VariableEncryption(declared);
        }
        var source = keySource.getIfAvailable();
        if (source != null) {
            return new VariableEncryption(new AesGcmVariableCodec(source));
        }
        if (properties.configured()) {
            return new VariableEncryption(new AesGcmVariableCodec(properties.toKeySource()));
        }
        return VariableEncryption.DISABLED;
    }
}
