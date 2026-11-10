package com.flowcortex.credit.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class CreditsControllerTest {

    private final CreditAccounts creditAccounts = mock(CreditAccounts.class);
    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final CreditsController controller = new CreditsController(creditAccounts, userDirectory);
    private final UUID userId = UUID.randomUUID();
    private final Jwt jwt = mock(Jwt.class);

    @Test
    void theBalanceEndpointAnswers503WhileCreditsAreSwitchedOff() {
        when(creditAccounts.enabled()).thenReturn(false);

        assertThatThrownBy(() -> controller.balance(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aTokenWhoseUserIsGoneIsNotFound() {
        when(creditAccounts.enabled()).thenReturn(true);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userDirectory.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.balance(jwt)).isInstanceOf(NotFoundException.class);
    }
}
