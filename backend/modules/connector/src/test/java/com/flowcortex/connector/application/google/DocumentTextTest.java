package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.connector.api.DriveFileText;
import com.flowcortex.connector.application.google.DocumentText.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTextTest {

    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int CAP = 50_000;
    private static final int EXPANSION_CAP = 20 * 1024 * 1024;

    /** A clock that moves {@code step} on every read, so a test sees how often the extractor consults it. */
    private static final class SteppingClock extends Clock {
        private final Instant start = Instant.parse("2026-11-20T10:00:00Z");
        private final Duration step;
        private final AtomicInteger calls = new AtomicInteger();

        SteppingClock(Duration step) {
            this.step = step;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return start.plus(step.multipliedBy(calls.getAndIncrement()));
        }
    }

    private final DocumentText plain = new DocumentText(new SteppingClock(Duration.ZERO));

    private static byte[] pdf(int pages, IntFunction<List<String>> lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int page = 1; page <= pages; page++) {
                PDPage pdPage = new PDPage();
                document.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(25, 760);
                    for (String line : lines.apply(page)) {
                        content.showText(line);
                        content.newLineAtOffset(0, -12);
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** One page whose content stream holds {@code shows} text-showing operators of {@code glyphs} characters each. */
    private static byte[] onePageOfShows(int shows, int glyphs) throws IOException {
        String text = "x".repeat(glyphs);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(25, 760);
                for (int i = 0; i < shows; i++) {
                    content.showText(text);
                    content.newLineAtOffset(0, -12);
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdfOfPages(int pages) throws IOException {
        return pdf(pages, page -> List.of("page-" + page));
    }

    private static byte[] encryptedPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String paragraph(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static byte[] docx(String documentXml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String document(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"" + W_NS
                + "\"><w:body>" + body + "</w:body></w:document>";
    }

    @Test
    void aPdfWithTextGivesItsTextAndTypePdf() throws IOException {
        DriveFileText result = plain.fromBytes(pdf(1, page -> List.of("Nguyen Van A", "Java developer")), PDF);

        assertThat(result.text()).isEqualTo("Nguyen Van A\nJava developer");
        assertThat(result.detectedType()).isEqualTo("pdf");
        assertThat(result.needsVision()).isFalse();
        assertThat(result.truncated()).isFalse();
        assertThat(result.warning()).isNull();
    }

    @Test
    void aPdfWithoutTextNeedsVision() throws IOException {
        DriveFileText result = plain.fromBytes(pdf(1, page -> List.of()), PDF);

        assertThat(result.text()).isEmpty();
        assertThat(result.needsVision()).isTrue();
        assertThat(result.detectedType()).isEqualTo("pdf");
        assertThat(result.warning()).isNull();
    }

    @Test
    void anEncryptedPdfIsWarnedAboutNotRead() throws IOException {
        DriveFileText result = plain.fromBytes(encryptedPdf(), PDF);

        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("encrypted");
        assertThat(result.needsVision()).isFalse();
    }

    @Test
    void aPdfIsReadUpToPage200() throws IOException {
        DriveFileText result = plain.fromBytes(pdfOfPages(250), PDF);

        assertThat(result.text()).contains("page-1\n").contains("page-200").doesNotContain("page-201");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aPdfStopsAtTheCharacterCapWithoutStrippingTheRemainingPages() throws IOException {
        SteppingClock clock = new SteppingClock(Duration.ZERO);
        byte[] bytes = pdf(150, page -> {
            List<String> lines = new ArrayList<>();
            lines.add("P" + page + "-" + "x".repeat(46));
            for (int i = 1; i < 20; i++) {
                lines.add("x".repeat(50));
            }
            return lines;
        });

        DriveFileText result = new DocumentText(clock).fromBytes(bytes, PDF);

        assertThat(result.text()).hasSize(CAP).contains("P50-").doesNotContain("P52-");
        assertThat(result.truncated()).isTrue();
        assertThat(clock.calls()).isLessThanOrEqualTo(1 + 51);
    }

    @Test
    void aSlowPdfIsAbandonedAtThePageBoundaryPastTwentySeconds() throws IOException {
        SteppingClock clock = new SteppingClock(Duration.ofSeconds(3));

        DriveFileText result = new DocumentText(clock).fromBytes(pdfOfPages(20), PDF);

        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("unreadable");
        assertThat(result.needsVision()).isFalse();
        assertThat(clock.calls()).isEqualTo(8);
    }

    @Test
    void aSinglePageOfTwoHundredThousandOperatorsIsAbandonedInsideThePagePastTwentySeconds() throws IOException {
        SteppingClock clock = new SteppingClock(Duration.ofSeconds(3));

        DriveFileText result = new DocumentText(clock).fromBytes(onePageOfShows(200_000, 1), PDF);

        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("unreadable");
        assertThat(result.needsVision()).isFalse();
        assertThat(clock.calls()).isEqualTo(8);
    }

    @Test
    void aSinglePageOfTwoHundredThousandGlyphsEndsTruncatedAtTheCapAndStopsBeingParsed() throws IOException {
        SteppingClock clock = new SteppingClock(Duration.ZERO);

        DriveFileText result = new DocumentText(clock).fromBytes(onePageOfShows(2_000, 100), PDF);

        assertThat(result.text()).hasSize(CAP);
        assertThat(result.truncated()).isTrue();
        assertThat(result.warning()).isNull();
        assertThat(clock.calls()).isEqualTo(4);
    }

    @Test
    void aDocxGivesOneLinePerParagraph() throws IOException {
        byte[] bytes = docx(document(paragraph("Họ tên") + paragraph("Kỹ năng: Java")));

        DriveFileText result = plain.fromBytes(bytes, DOCX);

        assertThat(result.text()).isEqualTo("Họ tên\nKỹ năng: Java");
        assertThat(result.detectedType()).isEqualTo("docx");
        assertThat(result.warning()).isNull();
    }

    @Test
    void aTabIsKeptAsATab() throws IOException {
        byte[] bytes = docx(document("<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t></w:r></w:p>"));

        assertThat(plain.fromBytes(bytes, DOCX).text()).isEqualTo("a\tb");
    }

    @Test
    void aDocxOfEightyThousandCharactersIsCutAtTheCap() throws IOException {
        byte[] bytes = docx(document(paragraph("y".repeat(40_000)) + paragraph("z".repeat(40_000))));

        DriveFileText result = plain.fromBytes(bytes, DOCX);

        assertThat(result.text()).hasSize(CAP);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aDocxThatExpandsPastTwentyMegabytesIsUnreadable() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"" + W_NS + "\"><w:body>").getBytes(StandardCharsets.UTF_8));
            byte[] blanks = " ".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 30; i++) {
                zip.write(blanks);
            }
            zip.write("</w:body></w:document>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] bomb = out.toByteArray();

        DriveFileText result = plain.fromBytes(bomb, DOCX);

        assertThat(bomb.length).isLessThan(200_000);
        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("unreadable");
    }

    @Test
    void theZipPartStopsAfterExactlyTheExpansionCap() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 30; i++) {
                zip.write(chunk);
            }
            zip.closeEntry();
        }

        long consumed = 0;
        try (InputStream part = DocumentText.ZipPart.open(out.toByteArray(), "word/document.xml", EXPANSION_CAP)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = part.read(buffer)) != -1) {
                consumed += read;
            }
            throw new AssertionError("the bomb was read to its end");
        } catch (IOException expected) {
            assertThat(consumed).isEqualTo(EXPANSION_CAP);
        }
    }

    @Test
    void aDocxDeclaringAnExternalEntityIsUnreadableAndLeaksNothing(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"" + secret.toUri() + "\">]>"
                + "<w:document xmlns:w=\"" + W_NS + "\"><w:body>" + paragraph("&e;") + "</w:body></w:document>";

        DriveFileText result = plain.fromBytes(docx(xml), DOCX);

        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("unreadable");
        assertThat(result.toString()).doesNotContain("TOP-SECRET-VALUE");
    }

    @Test
    void aDocxThatIsNotAZipIsUnreadable() {
        DriveFileText result = plain.fromBytes("not a zip at all".getBytes(StandardCharsets.UTF_8), DOCX);

        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("unreadable");
    }

    @Test
    void aSlowDocxIsAbandonedAtTheParagraphBoundaryPastTwentySeconds() throws IOException {
        SteppingClock clock = new SteppingClock(Duration.ofSeconds(3));
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            body.append(paragraph("line " + i));
        }

        DriveFileText result = new DocumentText(clock).fromBytes(docx(document(body.toString())), DOCX);

        assertThat(result.text()).isEmpty();
        assertThat(result.warning()).isEqualTo("unreadable");
        assertThat(clock.calls()).isEqualTo(8);
    }

    @Test
    void aTextFileIsDecodedAsUtf8() {
        DriveFileText result = plain.fromBytes("Kinh nghiệm 5 năm".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");

        assertThat(result.text()).isEqualTo("Kinh nghiệm 5 năm");
        assertThat(result.detectedType()).isEqualTo("text");
    }

    @Test
    void aTextFileOfEightyThousandCharactersIsCutAtTheCap() {
        DriveFileText result = plain.fromBytes("a".repeat(80_000).getBytes(StandardCharsets.UTF_8), "text/csv");

        assertThat(result.text()).hasSize(CAP);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void anExportedGoogleDocIsKeptAndCutAtTheCap() {
        DriveFileText kept = plain.fromExportedText("Nguyễn Văn A\nKinh nghiệm 5 năm");
        DriveFileText cut = plain.fromExportedText("b".repeat(80_000));

        assertThat(kept.text()).isEqualTo("Nguyễn Văn A\nKinh nghiệm 5 năm");
        assertThat(kept.detectedType()).isEqualTo("gdoc");
        assertThat(cut.text()).hasSize(CAP);
        assertThat(cut.truncated()).isTrue();
    }

    @Test
    void anImageNeedsVisionWithoutBytes() {
        DriveFileText result = plain.fromBytes(null, "image/png");

        assertThat(result.text()).isEmpty();
        assertThat(result.needsVision()).isTrue();
        assertThat(result.detectedType()).isEqualTo("image");
        assertThat(result.warning()).isNull();
    }

    @Test
    void mediaUnsupportedAndGoogleFileTypesAreNotRead() {
        assertThat(plain.fromBytes(null, "video/mp4")).isEqualTo(new DriveFileText("", "media", false, false, "media"));
        assertThat(plain.fromBytes(null, "application/zip"))
                .isEqualTo(new DriveFileText("", "unsupported", false, false, "unsupported"));
        assertThat(plain.fromBytes(null, "application/vnd.google-apps.spreadsheet"))
                .isEqualTo(new DriveFileText("", "gsuite", false, false, "googleFileType"));
    }

    @Test
    void classifyMapsEveryMimeFamilyOnce() {
        assertThat(DocumentText.classify("application/vnd.google-apps.document")).isEqualTo(Kind.GDOC);
        assertThat(DocumentText.classify("application/vnd.google-apps.folder")).isEqualTo(Kind.GSUITE);
        assertThat(DocumentText.classify(PDF)).isEqualTo(Kind.PDF);
        assertThat(DocumentText.classify(DOCX)).isEqualTo(Kind.DOCX);
        assertThat(DocumentText.classify("text/markdown")).isEqualTo(Kind.TEXT);
        assertThat(DocumentText.classify("image/jpeg")).isEqualTo(Kind.IMAGE);
        assertThat(DocumentText.classify("audio/mpeg")).isEqualTo(Kind.MEDIA);
        assertThat(DocumentText.classify("application/msword")).isEqualTo(Kind.UNSUPPORTED);
        assertThat(DocumentText.classify(null)).isEqualTo(Kind.UNSUPPORTED);
    }
}
