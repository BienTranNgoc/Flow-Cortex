package com.flowcortex.connector.web;

import com.flowcortex.connector.application.http.HttpConnectorService;
import com.flowcortex.connector.application.http.HttpConnectorService.Saved;
import com.flowcortex.connector.application.http.HttpConnectorService.SaveCommand;
import com.flowcortex.connector.application.http.HttpConnectorService.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/connectors/http} — a member's generic HTTP connectors. */
@RestController
@RequestMapping("/api/v1/connectors/http")
class HttpConnectorController {

    /**
     * {@code token} and the {@code extraHeaders} values are write-only. Field rules the member can break in a
     * connector-specific way (key, token, URL, header names) are answered by the service with their own message.
     */
    record SaveRequest(
            @Size(max = 100) String key,
            @Size(max = 100) String label,
            @NotNull @Pattern(regexp = "bearer|header|query") String authType,
            @Size(max = 4096) String token,
            @Size(max = 2048) String baseUrl,
            @Size(max = 100) String headerName,
            @Size(max = 100) String paramName,
            @Size(max = 64) Map<String, @Size(max = 4096) String> extraHeaders) {

        @Override
        public String toString() {
            return "SaveRequest[key=" + key + "]";
        }
    }

    private final HttpConnectorService service;
    private final ConnectorMember member;

    HttpConnectorController(HttpConnectorService service, ConnectorMember member) {
        this.service = service;
        this.member = member;
    }

    @PostMapping
    Saved save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveRequest request) {
        return service.save(member.id(jwt), new SaveCommand(request.key(), request.label(), request.authType(),
                request.token(), request.baseUrl(), request.headerName(), request.paramName(),
                request.extraHeaders()));
    }

    @GetMapping
    List<Summary> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(member.id(jwt));
    }
}
