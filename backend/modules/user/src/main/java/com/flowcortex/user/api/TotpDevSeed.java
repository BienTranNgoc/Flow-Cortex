package com.flowcortex.user.api;

/**
 * Dev-profile-only way to give a seeded account a fixed second-factor secret. Only the {@code dev}
 * profile registers an implementation, so no other profile can set a secret outside the enrolment flow.
 */
public interface TotpDevSeed {

    /**
     * Turns TOTP on for the account with {@code email} under {@code base32Secret}, without recovery codes.
     * A no-op when that account already has TOTP on.
     *
     * @return true when this call enrolled the account
     * @throws IllegalStateException if no account has that email
     * @throws IllegalArgumentException if the secret is not base32
     */
    boolean enrollWithSecret(String email, String base32Secret);
}
