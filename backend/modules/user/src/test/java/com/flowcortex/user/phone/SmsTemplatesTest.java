package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class SmsTemplatesTest {

    private static final String OTP = "Ma OTP {CODE}";
    private static final String CHANGED = "So dien thoai cua ban da doi";
    private static final String REVERTED = "So dien thoai cua ban da duoc khoi phuc";

    @Test
    void aCodeTemplateRendersTheCode() {
        SmsTemplates templates = SmsTemplates.validated(OTP, CHANGED, REVERTED);

        String text = templates.render(otp("123456"));

        assertThat(text).isEqualTo("Ma OTP 123456");
    }

    @Test
    void eachNoticeKindRendersItsOwnTextWhateverTheLocale() {
        SmsTemplates templates = SmsTemplates.validated(OTP, CHANGED, REVERTED);

        assertThat(templates.render(notice(SmsMessage.Kind.PHONE_CHANGED, Locale.ENGLISH))).isEqualTo(CHANGED);
        assertThat(templates.render(notice(SmsMessage.Kind.PHONE_CHANGE_REVERTED, Locale.ENGLISH)))
                .isEqualTo(REVERTED);
        assertThat(templates.render(notice(SmsMessage.Kind.PHONE_CHANGED, Locale.forLanguageTag("vi"))))
                .isEqualTo(CHANGED);
    }

    @Test
    void aCodeTemplateWithoutThePlaceholderIsRefused() {
        assertThatThrownBy(() -> SmsTemplates.validated("Ma OTP cua ban", CHANGED, REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.otp");
    }

    @Test
    void aCodeTemplateWithThePlaceholderTwiceIsRefused() {
        assertThatThrownBy(() -> SmsTemplates.validated("{CODE} la ma {CODE}", CHANGED, REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.otp");
    }

    @Test
    void anAccentedTemplateIsRefusedByName() {
        String accented = "So dien thoai da đổi ố";

        assertThatThrownBy(() -> SmsTemplates.validated(OTP, accented, REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.phone-changed")
                .hasMessageNotContaining(accented);
    }

    @Test
    void aTemplateOver160WithTheCodeIsRefused() {
        String tooLong = "a".repeat(161 - "{CODE}".length()) + "{CODE}";
        assertThat(tooLong).hasSize(161);

        assertThatThrownBy(() -> SmsTemplates.validated(tooLong, CHANGED, REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.otp");
        assertThatThrownBy(() -> SmsTemplates.validated(OTP, "a".repeat(161), REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.phone-changed");
    }

    @Test
    void aTemplateOf160IsAccepted() {
        String limit = "a".repeat(160 - "{CODE}".length()) + "{CODE}";
        assertThat(limit).hasSize(160);

        SmsTemplates templates = SmsTemplates.validated(limit, "b".repeat(160), REVERTED);

        assertThat(templates.render(otp("123456"))).hasSize(160);
    }

    @Test
    void aNoticeTemplateMayNotHoldThePlaceholder() {
        assertThatThrownBy(() -> SmsTemplates.validated(OTP, CHANGED, "Da khoi phuc {CODE}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.phone-change-reverted");
    }

    @Test
    void aMissingOrBlankTemplateIsRefusedByName() {
        assertThatThrownBy(() -> SmsTemplates.validated(null, CHANGED, REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.otp");
        assertThatThrownBy(() -> SmsTemplates.validated(OTP, "  ", REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.phone-changed");
    }

    @Test
    void aGsmBasicSetCharacterOutsideAsciiIsAccepted() {
        SmsTemplates templates = SmsTemplates.validated("Ma è {CODE}", CHANGED, REVERTED);

        assertThat(templates.render(otp("123456"))).isEqualTo("Ma è 123456");
    }

    @Test
    void aCharacterOfTheExtensionTableIsRefusedBecauseItCostsTwoSeptets() {
        assertThatThrownBy(() -> SmsTemplates.validated(OTP, "Doi [so]", REVERTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.vietguys.templates.phone-changed");
    }

    @Test
    void aCodeMessageNeedsItsCodeAndANoticeMayNotCarryOne() {
        Locale vi = Locale.forLanguageTag("vi");

        assertThatThrownBy(() -> new SmsMessage("+84912345678", SmsMessage.Kind.OTP, null, vi, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmsMessage("+84912345678", SmsMessage.Kind.PHONE_CHANGED, "123456", vi, "r"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoggedMessageNamesTheNumberByItsLastThreeDigitsAndNeverTheCode() {
        assertThat(otp("123456").toString()).contains("***678").doesNotContain("123456").doesNotContain("84912345678");
    }

    private static SmsMessage otp(String code) {
        return new SmsMessage("+84912345678", SmsMessage.Kind.OTP, code, Locale.forLanguageTag("vi"), "req-1");
    }

    private static SmsMessage notice(SmsMessage.Kind kind, Locale locale) {
        return new SmsMessage("+84912345678", kind, null, locale, "req-2");
    }
}
