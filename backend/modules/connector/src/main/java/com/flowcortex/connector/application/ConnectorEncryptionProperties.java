package com.flowcortex.connector.application;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.connectors.encryption}: the current key id and the keyring (id to 32-byte base64 key).
 * Keys arrive from {@code FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_<ID>}; validated in {@link ConnectorEncryptionConfig}.
 */
@ConfigurationProperties("flowcortex.connectors.encryption")
record ConnectorEncryptionProperties(String current, Map<String, String> keys) {
}
