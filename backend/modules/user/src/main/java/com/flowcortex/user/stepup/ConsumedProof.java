package com.flowcortex.user.stepup;

import java.time.Instant;
import java.util.UUID;

/** The row a successful spend returned: whose proof it was, for what, and until when. */
record ConsumedProof(UUID userId, String purpose, Instant expiresAt) {
}
