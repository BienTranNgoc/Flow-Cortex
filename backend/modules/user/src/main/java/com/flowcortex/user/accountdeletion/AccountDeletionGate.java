package com.flowcortex.user.accountdeletion;

import com.flowcortex.user.accountdeletion.AccountDeletionRequestRepository.Projection;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Asks whether an account has a deletion request that closes it. Reads the account's own projection
 * from the database on every call, never from the access token, so a request made on another device
 * applies to the next request here. A missing account is not held: the handler answers it.
 */
@Component
public class AccountDeletionGate {

    private final AccountDeletionRequestRepository requests;
    private final Clock clock;

    AccountDeletionGate(AccountDeletionRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    public boolean holds(UUID userId) {
        return view(userId).isPresent();
    }

    /** The account's deletion request while its state is {@code PENDING} or {@code EXPIRED}; empty otherwise. */
    public Optional<AccountDeletionView> view(UUID userId) {
        Optional<Projection> projection = requests.projection(userId);
        if (projection.isEmpty()) {
            return Optional.empty();
        }
        Projection row = projection.get();
        AccountDeletionState state = AccountDeletionState.of(row.requestedAt(), row.scheduledAt(), clock.instant());
        return state == AccountDeletionState.NONE
                ? Optional.empty()
                : Optional.of(new AccountDeletionView(state, row.requestedAt(), row.scheduledAt()));
    }
}
