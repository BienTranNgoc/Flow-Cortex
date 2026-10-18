package com.flowcortex.credit.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.infrastructure.CreditLedgerRepository;
import com.flowcortex.user.api.UserActivatedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignupGrantListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    private final CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
    private final SignupGrantListener listener = new SignupGrantListener(ledger, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    @Test
    void anActivatedAccountReceivesTheSignupGrant() {
        when(ledger.grant(any(), eq(userId), eq(SignupGrantListener.SIGNUP_GRANT), eq(NOW))).thenReturn(1);

        listener.on(new UserActivatedEvent(userId));

        verify(ledger).grant(any(), eq(userId), eq(SignupGrantListener.SIGNUP_GRANT), eq(NOW));
    }

    @Test
    void aRedeliveredEventAsksTheLedgerAgainAndTheLedgerKeepsOneGrant() {
        when(ledger.grant(any(), eq(userId), eq(SignupGrantListener.SIGNUP_GRANT), eq(NOW)))
                .thenReturn(1).thenReturn(0);

        listener.on(new UserActivatedEvent(userId));
        listener.on(new UserActivatedEvent(userId));

        verify(ledger, times(2)).grant(any(), eq(userId), eq(SignupGrantListener.SIGNUP_GRANT), eq(NOW));
    }
}
