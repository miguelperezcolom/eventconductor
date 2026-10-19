package io.mateu.workflow.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code workflow.encryption.*}: the keys sensitive variables are sealed with.
 *
 * <pre>
 * workflow.encryption.keys.2026-10=${EC_KEY_2026_10}   # base64 of 32 random bytes: openssl rand -base64 32
 * workflow.encryption.keys.2026-04=${EC_KEY_2026_04}   # an older key, kept to open what it sealed
 * workflow.encryption.active-key=2026-10               # optional with a single key
 * </pre>
 *
 * <p>No key, no encryption — and then a definition declaring {@code sensitiveVariables} is refused.
 */
@ConfigurationProperties(prefix = "workflow.encryption")
public class EncryptionProperties {

    /** The keys by id, each the base64 of 32 bytes. */
    private Map<String, String> keys = new LinkedHashMap<>();

    /** The id of the key new values are sealed with; optional when exactly one key is configured. */
    private String activeKey;

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : keys;
    }

    public String getActiveKey() {
        return activeKey;
    }

    public void setActiveKey(String activeKey) {
        this.activeKey = activeKey;
    }

    /** Whether any key is configured. */
    public boolean configured() {
        return keys != null && keys.values().stream().anyMatch(v -> v != null && !v.isBlank());
    }

    /** The configured keys as a source; fails, naming the key (never its value), on one that is not base64. */
    public EncryptionKeySource toKeySource() {
        var decoded = new LinkedHashMap<String, byte[]>();
        keys.forEach((id, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            try {
                decoded.put(id, Base64.getDecoder().decode(value.strip()));
            } catch (IllegalArgumentException e) {
                throw new EncryptionConfigurationException("workflow.encryption.keys." + id + " is not valid base64.");
            }
        });
        return EncryptionKeySource.of(activeKey == null || activeKey.isBlank() ? null : activeKey.strip(), decoded);
    }
}
