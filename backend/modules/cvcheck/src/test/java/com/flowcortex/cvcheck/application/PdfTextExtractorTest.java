package com.flowcortex.cvcheck.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfTextExtractorTest {

    @Test
    void extractsTextFromGeneratedPdf() throws IOException {
        byte[] pdfBytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(100, 700);
                stream.showText("Nguyen Van An - Java Developer");
                stream.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdfBytes = out.toByteArray();
        }

        String extracted = PdfTextExtractor.extract(pdfBytes);
        assertThat(extracted).contains("Nguyen Van An - Java Developer");
    }

    @Test
    void emptyBytesReturnsEmpty() throws IOException {
        assertThat(PdfTextExtractor.extract(new byte[0])).isEmpty();
        assertThat(PdfTextExtractor.extract(null)).isEmpty();
    }
}
