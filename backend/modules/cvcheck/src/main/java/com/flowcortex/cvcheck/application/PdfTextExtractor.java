package com.flowcortex.cvcheck.application;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfTextExtractor {

    public record ExtractedPdf(String text, int pageCount) {
    }

    private PdfTextExtractor() {
    }

    public static String extract(byte[] pdfBytes) throws IOException {
        return extractWithMeta(pdfBytes).text();
    }

    public static ExtractedPdf extractWithMeta(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return new ExtractedPdf("", 0);
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return new ExtractedPdf(text != null ? text.strip() : "", document.getNumberOfPages());
        }
    }
}
