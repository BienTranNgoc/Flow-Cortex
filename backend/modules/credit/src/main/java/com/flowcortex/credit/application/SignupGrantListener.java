package com.flowcortex.credit.application;

import com.flowcortex.credit.infrastructure.CreditLedgerRepository;
import com.flowcortex.user.api.UserCreatedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Every new account gets the signup grant and its wallet once the account has committed, whatever
 * the credits switch says, so a member has a balance the day credits are turned on. A redelivered
 * event changes nothing: the ledger allows one grant per user.
 */
@Component
class SignupGrantListener {

    static final long SIGNUP_GRANT = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(SignupGrantListener.class);

    private final CreditLedgerRepository ledger;
    private final Clock clock;

    SignupGrantListener(CreditLedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(UserCreatedEvent event) {
        if (ledger.grant(UUID.randomUUID(), event.userId(), SIGNUP_GRANT, Instant.now(clock)) == 1) {
            LOG.info("Credits: outcome=grant userId={} amount={}", event.userId(), SIGNUP_GRANT);
        } else {
            LOG.info("Credits: outcome=grant_duplicate userId={}", event.userId());
        }
    }
}
