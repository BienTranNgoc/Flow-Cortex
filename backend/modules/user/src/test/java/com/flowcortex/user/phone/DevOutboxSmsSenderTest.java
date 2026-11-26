package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.user.phone.SmsMessage.Kind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DevOutboxSmsSenderTest {

    private static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");
    private static final String PHONE = "+84912345678";
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);

    private final SmsOutboxRepository outbox = mock(SmsOutboxRepository.class);
    private final DevOutboxSmsSender sender =
            new DevOutboxSmsSender(outbox, Clock.fixed(NOW, ZoneOffset.UTC), LOCALE);

    @Test
    void aVietnameseMessageIsWrittenInVietnamese() {
        SmsDelivery delivery = sender.send(new SmsMessage(PHONE, Kind.OTP, "482913", VIETNAMESE, "r-1"));

        assertThat(delivery).isEqualTo(SmsDelivery.ACCEPTED);
        assertThat(written().getBody())
                .isEqualTo("Mã xác minh Flow-Cortex của bạn là 482913. Mã hết hạn sau 5 phút.");
        assertThat(written().getPhoneE164()).isEqualTo(PHONE);
        assertThat(written().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void anEnglishCodeMessageCarriesItsCode() {
        sender.send(new SmsMessage(PHONE, Kind.OTP, "482913", Locale.ENGLISH, "r-2"));

        assertThat(written().getBody()).isEqualTo("Your Flow-Cortex code is 482913. It expires in 5 minutes.");
    }

    @Test
    void eachNoticeKindIsWrittenFromItsOwnText() {
        sender.send(new SmsMessage(PHONE, Kind.PHONE_CHANGED, null, Locale.ENGLISH, "r-3"));
        sender.send(new SmsMessage(PHONE, Kind.PHONE_CHANGE_REVERTED, null, Locale.ENGLISH, "r-4"));

        ArgumentCaptor<SmsOutboxEntry> entries = ArgumentCaptor.forClass(SmsOutboxEntry.class);
        verify(outbox, times(2)).save(entries.capture());
        assertThat(entries.getAllValues()).extracting(SmsOutboxEntry::getBody).containsExactly(
                "Your Flow-Cortex phone number was changed. If this was not you, undo it within 24 hours in your "
                        + "profile or contact support.",
                "Your Flow-Cortex phone number change was undone. If this was not you, contact support.");
    }

    private SmsOutboxEntry written() {
        ArgumentCaptor<SmsOutboxEntry> entry = ArgumentCaptor.forClass(SmsOutboxEntry.class);
        verify(outbox).save(entry.capture());
        return entry.getValue();
    }
}
