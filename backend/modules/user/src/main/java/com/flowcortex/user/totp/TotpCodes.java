package com.flowcortex.user.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP as RFC 6238 defines it for authenticator apps: HMAC-SHA1, 6 digits, 30-second steps. A code
 * is accepted for the current step and the one on each side; {@link #match} reports which one, so
 * the caller can refuse a step it has already accepted.
 */
public final class TotpCodes {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final int STEP_SECONDS = 30;
    private static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final int MODULUS = 1_000_000;
    private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{" + DIGITS + "}");

    private TotpCodes() {
    }

    /** A new 160-bit secret as 32 base32 characters. */
    public static String newSecret(SecureRandom random) {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static long stepOf(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /** The six-digit code of {@code step} (zero-padded). */
    public static String codeAt(String base32Secret, long step) {
        return new String(codeBytes(base32Decode(base32Secret), step), StandardCharsets.US_ASCII);
    }

    /** Six digits, spaces ignored: the only shape {@link #match} can accept. */
    static boolean looksLikeCode(String code) {
        return code != null && SIX_DIGITS.matcher(code.replace(" ", "")).matches();
    }

    /**
     * The step {@code code} was made for, within one step of {@code now}, or empty when the code is
     * not six digits (spaces ignored) or matches none. All three steps are always compared, in
     * constant time, so timing does not tell which one was close.
     */
    public static OptionalLong match(String base32Secret, String code, Instant now) {
        if (!looksLikeCode(code)) {
            return OptionalLong.empty();
        }
        String digits = code.replace(" ", "");
        byte[] supplied = digits.getBytes(StandardCharsets.US_ASCII);
        byte[] key = base32Decode(base32Secret);
        long current = stepOf(now);
        long matched = -1;
        for (long step = Math.max(0, current - 1); step <= current + 1; step++) {
            if (MessageDigest.isEqual(codeBytes(key, step), supplied)) {
                matched = step;
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    static String base32Encode(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException for a character outside the upper-case base32 alphabet */
    static byte[] base32Decode(String base32) {
        String clean = base32.endsWith("=") ? base32.replaceAll("=+$", "") : base32;
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out;
    }

    private static byte[] codeBytes(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % MODULUS).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", ex);
        }
    }
}
