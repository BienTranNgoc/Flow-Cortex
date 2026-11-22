package com.flowcortex.user.phone;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM over a keyring. The stored value is {@code kid.iv.tag.ct} (base64 parts) and the
 * account name is the associated data, so a value copied to another account fails authentication.
 */
final class CredentialSealer {

    private static final String KEYS_PROPERTY = "flowcortex.sms.vietguys.keys.";
    private static final String CURRENT_KEY_PROPERTY = "flowcortex.sms.vietguys.current-key-id";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final String SEPARATOR = ".";

    /** A stored value that does not open: a tampered text, another account's, or a key this ring lacks. */
    static final class CredentialUnreadableException extends RuntimeException {

        CredentialUnreadableException() {
            super("Stored credential cannot be read");
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> keys;
    private final String currentKeyId;

    /**
     * @param base64Keys key id → base64 of 32 key bytes
     * @throws IllegalStateException naming the property of a key that is not usable, never its value
     */
    CredentialSealer(Map<String, String> base64Keys, String currentKeyId) {
        Map<String, SecretKey> ring = new HashMap<>();
        base64Keys.forEach((id, encoded) -> ring.put(id, decodeKey(id, encoded)));
        if (currentKeyId == null || !ring.containsKey(currentKeyId)) {
            throw new IllegalStateException(CURRENT_KEY_PROPERTY + " does not name a key of the ring");
        }
        this.keys = Map.copyOf(ring);
        this.currentKeyId = currentKeyId;
    }

    String seal(String account, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(account.getBytes(StandardCharsets.UTF_8));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int split = out.length - TAG_BYTES;
            Base64.Encoder base64 = Base64.getEncoder();
            return currentKeyId + SEPARATOR + base64.encodeToString(iv)
                    + SEPARATOR + base64.encodeToString(Arrays.copyOfRange(out, split, out.length))
                    + SEPARATOR + base64.encodeToString(Arrays.copyOfRange(out, 0, split));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Credential sealing failed", failure);
        }
    }

    /** @throws CredentialUnreadableException for any value that does not authenticate for this account */
    String open(String account, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\.", -1);
        if (parts.length != 4) {
            throw new CredentialUnreadableException();
        }
        SecretKey key = keys.get(parts[0]);
        if (key == null) {
            throw new CredentialUnreadableException();
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] iv = base64.decode(parts[1]);
            byte[] tag = base64.decode(parts[2]);
            byte[] ciphertext = base64.decode(parts[3]);
            if (iv.length != IV_BYTES || tag.length != TAG_BYTES) {
                throw new CredentialUnreadableException();
            }
            byte[] joined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, joined, 0, ciphertext.length);
            System.arraycopy(tag, 0, joined, ciphertext.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(account.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new CredentialUnreadableException();
        }
    }

    private static SecretKey decodeKey(String id, String encoded) {
        String property = KEYS_PROPERTY + id;
        if (id.contains(SEPARATOR)) {
            throw new IllegalStateException(property + " has a key id holding '" + SEPARATOR + "'");
        }
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(property + " is not set");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(property + " is not valid base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(property + " must decode to " + KEY_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
