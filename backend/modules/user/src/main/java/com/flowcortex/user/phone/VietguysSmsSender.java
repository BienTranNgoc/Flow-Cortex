package com.flowcortex.user.phone;

import com.flowcortex.user.phone.VietguysAnswer.Kind;
import com.flowcortex.user.phone.VietguysHttp.Answer;
import com.flowcortex.user.phone.VietguysHttp.NotSent;
import com.flowcortex.user.phone.VietguysHttp.Outcome;
import com.flowcortex.user.phone.VietguysHttp.Unconfirmed;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Sends each message as one VietGuys Brandname request. A refused access token is retried once with
 * a fresh one under the same {@code bid}: VietGuys did not accept the message, and a reused id
 * would answer duplicate if it somehow had. Only {@link SmsSendFailure} leaves; the log holds the
 * last three digits of the number and the result, never the text, the code or a token.
 */
final class VietguysSmsSender implements SmsSender {

    static final String SENDER = "vietguys";

    private static final Logger LOG = LoggerFactory.getLogger(VietguysSmsSender.class);
    private static final FormHttpMessageConverter FORM = new FormHttpMessageConverter();

    private final VietguysHttp http;
    private final VietguysTokenStore tokens;
    private final SmsTemplates templates;
    private final URI smsUrl;
    private final String username;
    private final String brandname;
    private final SmsCarrierTelemetry telemetry;

    VietguysSmsSender(VietguysHttp http, VietguysTokenStore tokens, SmsTemplates templates, URI smsUrl,
            String username, String brandname, SmsCarrierTelemetry telemetry) {
        this.http = http;
        this.tokens = tokens;
        this.templates = templates;
        this.smsUrl = smsUrl;
        this.username = username;
        this.brandname = brandname;
        this.telemetry = telemetry;
    }

    @Override
    public SmsDelivery send(SmsMessage message) {
        try {
            SmsDelivery delivery = deliver(message);
            record(message, delivery.name().toLowerCase(Locale.ROOT));
            return delivery;
        } catch (SmsSendFailure failure) {
            record(message, failure.reason() == SmsSendFailure.Reason.NUMBER_REJECTED
                    ? "number_rejected" : "unavailable");
            throw failure;
        }
    }

    private SmsDelivery deliver(SmsMessage message) {
        String text = render(message);
        String token = token();
        Kind kind = attempt(message, text, token);
        if (kind == Kind.AUTH_REJECTED) {
            tokens.discard(token);
            kind = attempt(message, text, token());
        }
        return switch (kind) {
            case ACCEPTED, DUPLICATE, UNCONFIRMED -> kind.delivery().orElseThrow();
            case NUMBER_REJECTED -> throw new SmsSendFailure(SmsSendFailure.Reason.NUMBER_REJECTED);
            case OUT_OF_CREDIT -> {
                telemetry.lowBalance();
                throw new SmsSendFailure(SmsSendFailure.Reason.UNAVAILABLE);
            }
            case AUTH_REJECTED, CONFIG_REJECTED, UNAVAILABLE -> throw new SmsSendFailure(SmsSendFailure.Reason.UNAVAILABLE);
        };
    }

    private String render(SmsMessage message) {
        try {
            return templates.render(message);
        } catch (RuntimeException failure) {
            LOG.error("VietGuys message not rendered: {}", failure.getClass().getName());
            throw new SmsSendFailure(SmsSendFailure.Reason.UNAVAILABLE);
        }
    }

    private String token() {
        try {
            return tokens.accessToken();
        } catch (VietguysTokenStore.TokenUnavailableException unavailable) {
            throw new SmsSendFailure(SmsSendFailure.Reason.UNAVAILABLE);
        } catch (RuntimeException failure) {
            LOG.error("VietGuys access token not read: {}", failure.getClass().getName());
            throw new SmsSendFailure(SmsSendFailure.Reason.UNAVAILABLE);
        }
    }

    /** One request and its reading; a failure before the request is written is unavailable, after it unconfirmed. */
    private Kind attempt(SmsMessage message, String text, String token) {
        byte[] body;
        String contentType;
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("u", username);
            form.add("pwd", token);
            form.add("from", brandname);
            form.add("phone", message.phoneE164().substring(1));
            form.add("sms", text);
            form.add("bid", message.requestId());
            form.add("type", "0");
            form.add("json", "1");
            Multipart multipart = encode(form);
            body = multipart.body();
            contentType = multipart.contentType();
        } catch (IOException | RuntimeException failure) {
            LOG.error("VietGuys request not encoded: {}", failure.getClass().getName());
            throw new SmsSendFailure(SmsSendFailure.Reason.UNAVAILABLE);
        }
        try {
            Outcome outcome = http.post(smsUrl, Map.of("Access-Token", token), contentType, body);
            return kindOf(outcome);
        } catch (RuntimeException failure) {
            LOG.error("VietGuys answer not read: {}", failure.getClass().getName());
            return Kind.UNCONFIRMED;
        }
    }

    private Kind kindOf(Outcome outcome) {
        return switch (outcome) {
            case NotSent notSent -> Kind.UNAVAILABLE;
            case Unconfirmed unconfirmed -> Kind.UNCONFIRMED;
            case Answer answer -> {
                Kind kind = VietguysAnswer.classify(answer.status(), answer.body());
                if (kind == Kind.CONFIG_REJECTED) {
                    LOG.error("vietguys_rejected code={}",
                            VietguysAnswer.errorCode(answer.body()).orElse(0));
                }
                yield kind;
            }
        };
    }

    private void record(SmsMessage message, String result) {
        telemetry.send(SENDER, result);
        LOG.info("VietGuys SMS phone={} result={}", PhoneNumbers.forLog(message.phoneE164()), result);
    }

    private record Multipart(String contentType, byte[] body) {
    }

    private static Multipart encode(MultiValueMap<String, Object> form) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        HttpHeaders headers = new HttpHeaders();
        FORM.write(form, MediaType.MULTIPART_FORM_DATA, new HttpOutputMessage() {
            @Override
            public OutputStream getBody() {
                return buffer;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        });
        MediaType type = headers.getContentType();
        if (type == null) {
            throw new IOException("multipart content type missing");
        }
        return new Multipart(type.toString(), buffer.toByteArray());
    }
}
