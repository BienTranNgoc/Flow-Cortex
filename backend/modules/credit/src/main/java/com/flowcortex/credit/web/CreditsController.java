package com.flowcortex.credit.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/credits} — the caller's own balance; 503 while credits are switched off. */
@RestController
@RequestMapping("/api/v1/credits")
class CreditsController {

    private final CreditAccounts creditAccounts;
    private final UserDirectory userDirectory;

    CreditsController(CreditAccounts creditAccounts, UserDirectory userDirectory) {
        this.creditAccounts = creditAccounts;
        this.userDirectory = userDirectory;
    }

    @GetMapping(value = "/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BalanceResponse> balance(@AuthenticationPrincipal Jwt jwt) {
        if (!creditAccounts.enabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        UUID id = UUID.fromString(jwt.getSubject());
        UUID userId = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
        return ResponseEntity.ok(new BalanceResponse(creditAccounts.balance(userId)));
    }

    record BalanceResponse(long balance) {
    }
}
