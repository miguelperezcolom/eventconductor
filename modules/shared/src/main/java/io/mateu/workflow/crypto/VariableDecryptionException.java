package io.mateu.workflow.crypto;

/**
 * A sealed value could not be opened: its key is not configured, or it was tampered with. The message
 * names the key id and what went wrong — never a value, plaintext or ciphertext.
 */
public class VariableDecryptionException extends RuntimeException {

    public VariableDecryptionException(String message) {
        super(message);
    }

    public VariableDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
