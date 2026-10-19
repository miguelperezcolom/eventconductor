package io.mateu.workflow.crypto;

/** The encryption configuration cannot be used: a key that is not 32 bytes of base64, an unknown active key… */
public class EncryptionConfigurationException extends IllegalStateException {

    public EncryptionConfigurationException(String message) {
        super(message);
    }
}
