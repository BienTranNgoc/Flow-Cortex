package com.flowcortex.connector.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** Builds a real {@link ConnectorFlowSealer} over a fresh test keyring for tests outside this package. */
public final class FlowSealerFixture {

    private FlowSealerFixture() {
    }

    public static ConnectorFlowSealer sealer() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        return new ConnectorFlowSealer(ConnectorEncryptionConfig.buildCipher("k1", Map.of("k1", encoded)));
    }
}
