package com.flowcortex.user.totp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals TOTP secrets with AES-256-GCM over a keyring and derives the key recovery codes are hashed
 * under. A stored secret is {@code kid.iv.tag.ct} (base64 parts) with the account id as associated
 * data, so a value copied to another account fails authentication. Recovery codes are HMAC-SHA256
 * under HKDF-SHA256(key, "totp-recovery-code"), one derivation per key id.
 */
final class TotpSecretCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final byte[] RECOVERY_INFO = "totp-recovery-code".getBytes(StandardCharsets.UTF_8);
    private static final String UNREADABLE = "Stored TOTP secret cannot be read";

    private final SecureRandom random = new SecureRandom();
    private final String currentKeyId;
    private final Map<String, SecretKeySpec> sealingKeys;
    private final Map<String, SecretKeySpec> recoveryKeys;

    /** @throws IllegalArgumentException when the ring is empty, lacks the current key or holds a key that is not 32 base64 bytes */
    TotpSecretCipher(TotpProperties properties) {
        Map<String, String> ring = properties.keys();
        if (ring.isEmpty()) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.totp.keys has no entry: set FLOWCORTEX_AUTH_TOTP_KEYS_K1 (32 random bytes in base64)");
        }
        if (!ring.containsKey(properties.currentKeyId())) {
            throw new IllegalArgumentException("flowcortex.auth.totp.current-key-id '" + properties.currentKeyId()
                    + "' is not an entry of flowcortex.auth.totp.keys");
        }
        Map<String, SecretKeySpec> sealing = new LinkedHashMap<>();
        Map<String, SecretKeySpec> recovery = new LinkedHashMap<>();
        ring.forEach((id, encoded) -> {
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("flowcortex.auth.totp.keys id '" + id
                        + "' must be 1-32 letters, digits, '-' or '_'");
            }
            byte[] key = decode(id, encoded);
            sealing.put(id, new SecretKeySpec(key, "AES"));
            recovery.put(id, new SecretKeySpec(hkdf(key), "HmacSHA256"));
            Arrays.fill(key, (byte) 0);
        });
        this.currentKeyId = properties.currentKeyId();
        this.sealingKeys = Map.copyOf(sealing);
        this.recoveryKeys = Map.copyOf(recovery);
    }

    String currentKeyId() {
        return currentKeyId;
    }

    Set<String> keyIds() {
        return sealingKeys.keySet();
    }

    String encrypt(UUID userId, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealingKeys.get(currentKeyId), new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(associatedData(userId));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int split = out.length - TAG_BYTES;
            Base64.Encoder base64 = Base64.getEncoder();
            return currentKeyId + "." + base64.encodeToString(iv)
                    + "." + base64.encodeToString(Arrays.copyOfRange(out, split, out.length))
                    + "." + base64.encodeToString(Arrays.copyOfRange(out, 0, split));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("TOTP secret encryption failed", ex);
        }
    }

    /** @throws TotpSecretUnreadableException for any value that does not authenticate for this account */
    String decrypt(UUID userId, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\.", -1);
        if (parts.length != 4) {
            throw new TotpSecretUnreadableException();
        }
        SecretKeySpec key = sealingKeys.get(parts[0]);
        if (key == null) {
            throw new TotpSecretUnreadableException();
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] iv = base64.decode(parts[1]);
            byte[] tag = base64.decode(parts[2]);
            byte[] ct = base64.decode(parts[3]);
            if (iv.length != IV_BYTES || tag.length != TAG_BYTES) {
                throw new TotpSecretUnreadableException();
            }
            byte[] joined = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, joined, 0, ct.length);
            System.arraycopy(tag, 0, joined, ct.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(associatedData(userId));
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new TotpSecretUnreadableException();
        }
    }

    /**
     * Lower-case hex HMAC-SHA256 of an already normalized recovery code under the recovery key
     * derived from ring entry {@code keyId}.
     *
     * @throws IllegalArgumentException when {@code keyId} is not in the ring
     */
    String recoveryHash(String keyId, String normalizedCode) {
        SecretKeySpec key = recoveryKeys.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException("Unknown TOTP key id");
        }
        return HexFormat.of().formatHex(hmac(key, normalizedCode.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] associatedData(UUID userId) {
        return userId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decode(String id, String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("flowcortex.auth.totp.keys." + id + " is not base64", ex);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.totp.keys." + id + " must be " + KEY_BYTES + " bytes in base64, was " + key.length);
        }
        return key;
    }

    /** HKDF-SHA256 (RFC 5869) with an empty salt, {@code RECOVERY_INFO} and one 32-byte output block. */
    private static byte[] hkdf(byte[] inputKey) {
        byte[] pseudoRandomKey = hmac(new SecretKeySpec(new byte[KEY_BYTES], "HmacSHA256"), inputKey);
        byte[] block = Arrays.copyOf(RECOVERY_INFO, RECOVERY_INFO.length + 1);
        block[RECOVERY_INFO.length] = 1;
        return hmac(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"), block);
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    /** The stored secret is absent, malformed, under an unknown key, or not bound to this account. */
    static final class TotpSecretUnreadableException extends RuntimeException {

        TotpSecretUnreadableException() {
            super(UNREADABLE);
        }
    }
}
