package com.flowcortex.connector.application;

import com.flowcortex.connector.api.CredentialUnreadableException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM over a keyring. The stored value is {@code kid.iv.tag.ct} (base64 parts); the
 * member id and provider are the associated data, so a value copied to another row fails
 * authentication.
 */
final class CredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final String UNREADABLE = "Stored credential cannot be read";

    private final SecureRandom random = new SecureRandom();
    private final String currentId;
    private final Map<String, SecretKey> keys;

    CredentialCipher(String currentId, Map<String, SecretKey> keys) {
        this.currentId = currentId;
        this.keys = Map.copyOf(keys);
    }

    String encrypt(UUID userId, String provider, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentId), new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(associatedData(userId, provider));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int split = out.length - TAG_BYTES;
            Base64.Encoder base64 = Base64.getEncoder();
            return currentId + "." + base64.encodeToString(iv)
                    + "." + base64.encodeToString(Arrays.copyOfRange(out, split, out.length))
                    + "." + base64.encodeToString(Arrays.copyOfRange(out, 0, split));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Credential encryption failed", ex);
        }
    }

    /** @throws CredentialUnreadableException for any value that does not authenticate for this member and provider */
    String decrypt(UUID userId, String provider, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\.", -1);
        if (parts.length != 4) {
            throw unreadable(provider);
        }
        SecretKey key = keys.get(parts[0]);
        if (key == null) {
            throw unreadable(provider);
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] iv = base64.decode(parts[1]);
            byte[] tag = base64.decode(parts[2]);
            byte[] ct = base64.decode(parts[3]);
            if (iv.length != IV_BYTES || tag.length != TAG_BYTES) {
                throw unreadable(provider);
            }
            byte[] joined = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, joined, 0, ct.length);
            System.arraycopy(tag, 0, joined, ct.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(associatedData(userId, provider));
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw unreadable(provider);
        }
    }

    private static byte[] associatedData(UUID userId, String provider) {
        return (userId + "|" + provider).getBytes(StandardCharsets.UTF_8);
    }

    private static CredentialUnreadableException unreadable(String provider) {
        return new CredentialUnreadableException(UNREADABLE + " (provider " + provider + ")");
    }
}
