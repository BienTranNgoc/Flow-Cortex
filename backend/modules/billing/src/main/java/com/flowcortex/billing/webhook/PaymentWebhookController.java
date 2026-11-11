package com.flowcortex.billing.webhook;

import com.flowcortex.billing.momo.MomoCallbackRoute;
import com.flowcortex.billing.momo.MomoCallbackVerifier;
import com.flowcortex.billing.momo.MomoProperties;
import com.flowcortex.billing.provider.PaymentProviders;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives MoMo's payment callback. Called by MoMo's servers, so it carries no token: access is the source
 * address allowlist, checked before the body is read. Every answer is a bare status.
 */
@Hidden
@RestController
class PaymentWebhookController {

    private final PaymentProviders providers;
    private final CallbackSourceAllowlist allowlist;
    private final PaymentWebhookIngest ingest;
    private final WebhookTelemetry telemetry;
    private final Clock clock;

    PaymentWebhookController(PaymentProviders providers, MomoProperties properties, PaymentWebhookIngest ingest,
            WebhookTelemetry telemetry, Clock clock) {
        this.providers = providers;
        this.allowlist = new CallbackSourceAllowlist(
                CallbackSourceAllowlist.parse(properties.callbackSourceAllowlist()).entries());
        this.ingest = ingest;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @PostMapping(MomoCallbackRoute.PATH)
    ResponseEntity<Void> momo(HttpServletRequest request) {
        long started = System.nanoTime();
        if (providers.find(PaymentWebhookIngest.PROVIDER).isEmpty()) {
            return answer(HttpStatus.NOT_FOUND, WebhookTelemetry.DISABLED, "provider_disabled", started);
        }
        String remoteAddress = request.getRemoteAddr();
        if (!allowlist.admits(remoteAddress)) {
            return answer(HttpStatus.FORBIDDEN, WebhookTelemetry.REFUSED_SOURCE, "source_not_allowlisted", started);
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MomoCallbackVerifier.MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            telemetry.unreadableBody(PaymentWebhookIngest.PROVIDER);
            return answer(HttpStatus.SERVICE_UNAVAILABLE, WebhookTelemetry.REJECTED, "body_unreadable", started);
        }
        return ResponseEntity.status(ingest.handle(body, remoteAddress, clock.instant())).build();
    }

    private ResponseEntity<Void> answer(HttpStatus status, String disposition, String reason, long started) {
        telemetry.handled(PaymentWebhookIngest.PROVIDER, disposition, reason,
                Duration.ofNanos(System.nanoTime() - started));
        return ResponseEntity.status(status).build();
    }
}
