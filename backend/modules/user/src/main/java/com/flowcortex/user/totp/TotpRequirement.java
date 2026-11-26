package com.flowcortex.user.totp;

/**
 * Whether console accounts must sign in with a second factor. Callers read it through this
 * interface rather than {@link TotpProperties} so a test can flip it at runtime.
 */
public interface TotpRequirement {

    boolean adminRequired();

    /** The configured value ({@code flowcortex.auth.totp.admin-required}). */
    final class Configured implements TotpRequirement {

        private final TotpProperties properties;

        Configured(TotpProperties properties) {
            this.properties = properties;
        }

        @Override
        public boolean adminRequired() {
            return properties.adminRequired();
        }
    }
}
