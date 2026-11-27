package com.flowcortex.user.totp;

import com.flowcortex.user.api.TotpDevSeed;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link TotpDevSeed} over the same repositories and keyring the enrolment flow uses. */
@Component
@Profile("dev")
class DevTotpSeed implements TotpDevSeed {

    private final AppUserRepository users;
    private final UserTotpRepository totps;
    private final TotpSecretCipher cipher;

    DevTotpSeed(AppUserRepository users, UserTotpRepository totps, TotpSecretCipher cipher) {
        this.users = users;
        this.totps = totps;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public boolean enrollWithSecret(String email, String base32Secret) {
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new IllegalArgumentException("A TOTP secret is required");
        }
        TotpCodes.base32Decode(base32Secret);
        UUID userId = users.findIdByEmail(email)
                .orElseThrow(() -> new IllegalStateException("No account to enrol for " + email));
        if (totps.isEnabled(userId)) {
            return false;
        }
        totps.savePending(userId, cipher.encrypt(userId, base32Secret));
        // Step 0: no code has been accepted yet, so the first code of any step passes the replay check.
        return totps.enable(userId, 0) == 1;
    }
}
