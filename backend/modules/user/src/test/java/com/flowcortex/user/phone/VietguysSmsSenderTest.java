package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.user.phone.SmsMessage.Kind;
import com.flowcortex.user.phone.VietguysHttp.Answer;
import com.flowcortex.user.phone.VietguysHttp.NotSent;
import com.flowcortex.user.phone.VietguysHttp.Unconfirmed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VietguysSmsSenderTest {

    private static final URI SMS_URL = URI.create("https://sms.example.test:4438/api/index.php");
    private static final String PHONE = "+84912345678";
    private static final SmsMessage CODE = new SmsMessage(PHONE, Kind.OTP, "482913", Locale.forLanguageTag("vi"), "bid-1");

    private final VietguysHttp http = mock(VietguysHttp.class);
    private final VietguysTokenStore tokens = mock(VietguysTokenStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final VietguysSmsSender sender = new VietguysSmsSender(http, tokens,
            SmsTemplates.validated("Ma xac minh cua ban la {CODE}", "So dien thoai da doi", "Da hoan tac"),
            SMS_URL, "demo-user", "FLOWCORTEX", new SmsCarrierTelemetry(meters));

    @Test
    void aCodeIsOneMultipartRequestWithTheTokenInTheHeader() {
        when(tokens.accessToken()).thenReturn("tok-1");
        when(http.post(any(), anyMap(), anyString(), any())).thenReturn(answer(200, "{\"error\":0,\"msgid\":\"1\"}"));

        SmsDelivery delivery = sender.send(CODE);

        assertThat(delivery).isEqualTo(SmsDelivery.ACCEPTED);
        ArgumentCaptor<Map<String, String>> headers = headers();
        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(http).post(any(URI.class), headers.capture(), contentType.capture(), body.capture());
        assertThat(headers.getValue()).containsEntry("Access-Token", "tok-1");
        assertThat(contentType.getValue()).startsWith("multipart/form-data");
        String sent = new String(body.getValue(), StandardCharsets.UTF_8);
        assertThat(field(sent, "u")).isEqualTo("demo-user");
        assertThat(field(sent, "pwd")).isEqualTo("tok-1");
        assertThat(field(sent, "from")).isEqualTo("FLOWCORTEX");
        assertThat(field(sent, "phone")).isEqualTo("84912345678");
        assertThat(field(sent, "sms")).isEqualTo("Ma xac minh cua ban la 482913");
        assertThat(field(sent, "bid")).isEqualTo("bid-1");
        assertThat(field(sent, "type")).isEqualTo("0");
        assertThat(field(sent, "json")).isEqualTo("1");
        assertThat(meters.get("sms.send").tag("sender", "vietguys").tag("result", "accepted").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aRefusedTokenIsRetriedOnceWithAFreshToken() {
        when(tokens.accessToken()).thenReturn("old", "new");
        when(http.post(any(), anyMap(), anyString(), any()))
                .thenReturn(answer(401, "{\"error\":1,\"error_code\":-5}"), answer(200, "{\"error\":0}"));

        SmsDelivery delivery = sender.send(CODE);

        assertThat(delivery).isEqualTo(SmsDelivery.ACCEPTED);
        verify(tokens).discard("old");
        ArgumentCaptor<Map<String, String>> headers = headers();
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(http, times(2)).post(any(URI.class), headers.capture(), anyString(), body.capture());
        assertThat(headers.getAllValues()).extracting(h -> h.get("Access-Token")).containsExactly("old", "new");
        List<String> sent = body.getAllValues().stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList();
        assertThat(sent).extracting(b -> field(b, "bid")).containsExactly("bid-1", "bid-1");
        assertThat(field(sent.get(1), "pwd")).isEqualTo("new");
    }

    @Test
    void aSecondRefusalIsUnavailable() {
        when(tokens.accessToken()).thenReturn("old", "new");
        when(http.post(any(), anyMap(), anyString(), any()))
                .thenReturn(answer(401, "{\"error\":1,\"error_code\":-5}"), answer(200, "{\"error\":1,\"error_code\":12}"));

        assertThatThrownBy(() -> sender.send(CODE)).isInstanceOfSatisfying(SmsSendFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(SmsSendFailure.Reason.UNAVAILABLE));

        verify(http, times(2)).post(any(), anyMap(), anyString(), any());
    }

    @Test
    void aNotSentPostIsUnavailableAndAnUnconfirmedOneIsSent() {
        when(tokens.accessToken()).thenReturn("tok-1");
        when(http.post(any(), anyMap(), anyString(), any()))
                .thenReturn(new NotSent("java.io.IOException"), new Unconfirmed("java.net.SocketTimeoutException"));

        assertThatThrownBy(() -> sender.send(CODE)).isInstanceOfSatisfying(SmsSendFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(SmsSendFailure.Reason.UNAVAILABLE));
        assertThat(sender.send(CODE)).isEqualTo(SmsDelivery.UNCONFIRMED);
        verify(http, times(2)).post(any(), anyMap(), anyString(), any());
    }

    @Test
    void aRefusedNumberIsReportedAsSuch() {
        when(tokens.accessToken()).thenReturn("tok-1");
        when(http.post(any(), anyMap(), anyString(), any())).thenReturn(answer(200, "{\"error\":1,\"error_code\":-10}"));

        assertThatThrownBy(() -> sender.send(CODE)).isInstanceOfSatisfying(SmsSendFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(SmsSendFailure.Reason.NUMBER_REJECTED));
    }

    @Test
    void outOfCreditIsUnavailableAndCountsLowBalance() {
        when(tokens.accessToken()).thenReturn("tok-1");
        when(http.post(any(), anyMap(), anyString(), any())).thenReturn(answer(200, "{\"error\":1,\"error_code\":-9}"));

        assertThatThrownBy(() -> sender.send(CODE)).isInstanceOf(SmsSendFailure.class);

        assertThat(meters.get("sms.carrier.low_balance").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("sms.send").tag("sender", "vietguys").tag("result", "unavailable").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aTokenFailureIsUnavailableWithNoSmsRequest() {
        when(tokens.accessToken()).thenThrow(new VietguysTokenStore.TokenUnavailableException("reseed_required"));

        assertThatThrownBy(() -> sender.send(CODE)).isInstanceOfSatisfying(SmsSendFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(SmsSendFailure.Reason.UNAVAILABLE));

        verify(http, never()).post(any(), anyMap(), anyString(), any());
    }

    @Test
    void aNoticeIsSentAsItsTemplate() {
        when(tokens.accessToken()).thenReturn("tok-1");
        when(http.post(any(), anyMap(), anyString(), any())).thenReturn(answer(200, "{\"error\":0}"));

        sender.send(new SmsMessage(PHONE, Kind.PHONE_CHANGED, null, Locale.ENGLISH, "bid-2"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(http).post(any(URI.class), anyMap(), anyString(), body.capture());
        assertThat(field(new String(body.getValue(), StandardCharsets.UTF_8), "sms")).isEqualTo("So dien thoai da doi");
    }

    private static Answer answer(int status, String json) {
        return new Answer(status, json.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, String>> headers() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private static String field(String multipart, String name) {
        Matcher matcher = Pattern.compile("name=\"" + name + "\"[^\\r]*\\r\\n(?:[^\\r]+\\r\\n)*\\r\\n(.*?)\\r\\n--",
                Pattern.DOTALL).matcher(multipart);
        assertThat(matcher.find()).as("field %s", name).isTrue();
        return matcher.group(1);
    }
}
