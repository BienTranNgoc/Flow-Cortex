package com.flowcortex.connector.web;

import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/connectors} — routes that are the same for every provider. */
@RestController
@RequestMapping("/api/v1/connectors")
class ConnectorController {

    private final ConnectorCredentials credentials;
    private final ConnectorMember member;

    ConnectorController(ConnectorCredentials credentials, ConnectorMember member) {
        this.credentials = credentials;
        this.member = member;
    }

    /** Works with usage off and with a connector switch off: a member can always remove their own secret. */
    @DeleteMapping("/{provider}")
    ResponseEntity<Void> disconnect(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider) {
        UUID memberId = member.id(jwt);
        if (!ConnectorCredentials.PROVIDER_PATTERN.matcher(provider).matches()
                || !credentials.disconnect(memberId, provider)) {
            throw ConnectorException.of(Reason.NOT_CONNECTED);
        }
        return ResponseEntity.noContent().build();
    }
}
