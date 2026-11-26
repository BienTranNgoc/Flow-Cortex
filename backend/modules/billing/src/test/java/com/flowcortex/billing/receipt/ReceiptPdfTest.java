package com.flowcortex.billing.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.receipt.Receipt.Line;
import com.flowcortex.billing.receipt.Receipt.Seller;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class ReceiptPdfTest {

    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
    private static final Seller NODI = new Seller("Công ty TNHH Nodi", "0312345678", "12 Nguyễn Huệ, Quận 1, TP.HCM",
            "billing@nodi.vn");

    private static ReceiptCopy copy(Locale locale) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);
        return new ReceiptCopy(messages, locale);
    }

    private static Receipt receipt(String description, Seller seller) {
        return new Receipt(UUID.randomUUID(), ORDER_REF, "topup", Instant.parse("2026-11-26T03:04:00Z"), "momo", "wallet",
                "4111222333", seller, List.of(new Line(description, 1, 90_909, 90_909)), 90_909, 1_000, 9_091, 100_000,
                "VND", AmountInWords.vietnamese(100_000));
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    @Test
    void vietnameseLettersAndLabelsAreKept() throws IOException {
        String description = "Gói Chuyên nghiệp — đăng ký tháng";

        String text = textOf(ReceiptPdf.render(receipt(description, NODI), copy(VI)));

        assertThat(text).contains("Thanh toán thành công", "Thuế GTGT", ORDER_REF, "100.000 ₫", description,
                "Công ty TNHH Nodi", "0312345678", "Một trăm nghìn đồng");
    }

    @Test
    void englishCopyReadsInEnglish() throws IOException {
        String text = textOf(ReceiptPdf.render(receipt("Top-up", NODI), copy(Locale.ENGLISH)));

        assertThat(text).contains("Payment successful", "VAT (10%)", "100.000 ₫", "90.909 ₫", "9.091 ₫");
    }

    @Test
    void aLongDescriptionWrapsInsideTheRightMargin() throws IOException {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            words.add("gói" + i % 10);
        }
        String description = String.join(" ", words);
        assertThat(description).hasSize(199);

        byte[] pdf = ReceiptPdf.render(receipt(description, NODI), copy(VI));

        assertThat(textOf(pdf)).contains(words);
        float limit = 595.28f - ReceiptPdf.MARGIN + 0.5f;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<Float> ends = new ArrayList<>();
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    ends.add(text.getXDirAdj() + text.getWidthDirAdj());
                }
            }.getText(document);
            assertThat(ends).isNotEmpty().allSatisfy(end -> assertThat(end).isLessThanOrEqualTo(limit));
        }
    }

    @Test
    void aLongUnbrokenValueIsBrokenInsteadOfRunningPastTheMargin() throws IOException {
        byte[] pdf = ReceiptPdf.render(receipt("x".repeat(200), NODI), copy(VI));

        float limit = 595.28f - ReceiptPdf.MARGIN + 0.5f;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<Float> ends = new ArrayList<>();
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    ends.add(text.getXDirAdj() + text.getWidthDirAdj());
                }
            }.getText(document);
            assertThat(ends).isNotEmpty().allSatisfy(end -> assertThat(end).isLessThanOrEqualTo(limit));
        }
    }

    @Test
    void letterPairsTheFontFusesIntoLigaturesStayTheirLettersInTheTextLayer() throws IOException {
        String orderRef = "fc_ffe0fff1ffffffffffffffffffffffff";
        String description = "office offline affirms confirms fi fl ffi ffl";
        Receipt receipt = new Receipt(UUID.randomUUID(), orderRef, "topup", Instant.parse("2026-11-26T03:04:00Z"),
                "momo", "wallet", "4111222333", NODI, List.of(new Line(description, 1, 90_909, 90_909)), 90_909,
                1_000, 9_091, 100_000, "VND", AmountInWords.vietnamese(100_000));
        ReceiptCopy english = copy(Locale.ENGLISH);

        String text = textOf(ReceiptPdf.render(receipt, english));

        assertThat(text).contains(orderRef, description, english.note());
    }

    @Test
    void aCharacterTheFontCannotDrawBecomesOneQuestionMark() throws IOException {
        Seller rocket = new Seller("Nodi 🚀", null, null, null);

        String text = textOf(ReceiptPdf.render(receipt("Top-up", rocket), copy(VI)));

        assertThat(text).contains("Nodi ?").doesNotContain("Nodi ??");
    }

    @Test
    void aDecomposedLetterIsDrawnLikeItsPrecomposedForm() throws IOException {
        String decomposed = "Ngộ Van";
        Seller seller = new Seller(decomposed, null, null, null);

        String text = textOf(ReceiptPdf.render(receipt("Top-up", seller), copy(VI)));

        assertThat(text).contains("Ngộ Van").doesNotContain("?");
    }

    @Test
    void noLegalNamePrintsThePlaceholder() throws IOException {
        Seller unset = new Seller(null, null, null, null);

        assertThat(textOf(ReceiptPdf.render(receipt("Top-up", unset), copy(Locale.ENGLISH))))
                .contains("Provider details are being set up.");
        assertThat(textOf(ReceiptPdf.render(receipt("Top-up", unset), copy(VI))))
                .contains("Thông tin đơn vị cung cấp đang được thiết lập.");
    }

    @Test
    void contentLongerThanOnePageContinuesOnTheNext() throws IOException {
        String address = "đường ".repeat(900) + "cuối-cùng";
        Seller seller = new Seller("Nodi", null, address, null);

        byte[] pdf = ReceiptPdf.render(receipt("Top-up", seller), copy(VI));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
        }
        assertThat(textOf(pdf)).contains("cuối-cùng", "Tổng cộng");
    }

    @Test
    void bothFontsAreEmbedded() throws IOException {
        try (PDDocument document = Loader.loadPDF(ReceiptPdf.render(receipt("Top-up", NODI), copy(VI)))) {
            PDPage page = document.getPage(0);
            List<String> names = new ArrayList<>();
            for (COSName name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                names.add(font.getName());
                assertThat(font.isEmbedded()).isTrue();
            }
            assertThat(names).hasSize(2).allSatisfy(name -> assertThat(name).contains("BeVietnamPro"));
        }
    }
}
