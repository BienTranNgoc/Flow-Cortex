package com.flowcortex.billing.receipt;

import com.flowcortex.billing.receipt.Receipt.Line;
import com.flowcortex.billing.receipt.Receipt.Seller;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Draws a receipt as an A4 document with Be Vietnam Pro embedded. Text is wrapped by measured width into its
 * column, a block that would pass the bottom margin continues on a new page, and a character the font cannot
 * draw prints as {@code ?} so a stray symbol in a name never fails the document.
 */
final class ReceiptPdf {

    /** Page margin in points; also the limit the tests hold every line to. */
    static final float MARGIN = 48f;

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT = PAGE_WIDTH - 2 * MARGIN;
    private static final float COLUMN_GAP = 16f;
    private static final float NAME_WIDTH = 235f;
    private static final float QUANTITY_WIDTH = 54f;
    private static final float UNIT_PRICE_WIDTH = 100f;

    private static final float BODY = 10.5f;
    private static final float SMALL = 9f;
    private static final float LEADING = 1.4f;

    private static final Color INK = new Color(0x1F2937);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color ACCENT = new Color(0x15803D);
    private static final Color RULE = new Color(0xE5E7EB);

    private static final byte[] REGULAR = font("billing/receipt/fonts/be-vietnam-pro-400.ttf");
    private static final byte[] BOLD = font("billing/receipt/fonts/be-vietnam-pro-700.ttf");

    private ReceiptPdf() {
    }

    static byte[] render(Receipt receipt, ReceiptCopy copy) {
        try (PDDocument document = new PDDocument(); Sheet sheet = new Sheet(document)) {
            draw(sheet, receipt, copy);
            sheet.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The receipt PDF could not be drawn", e);
        }
    }

    private static void draw(Sheet sheet, Receipt receipt, ReceiptCopy copy) throws IOException {
        sheet.paragraph(sheet.bold, 11, ACCENT, MARGIN, CONTENT, copy.brand());
        sheet.gap(6);
        sheet.paragraph(sheet.bold, 20, INK, MARGIN, CONTENT, copy.title());
        sheet.gap(2);
        sheet.paragraph(sheet.regular, BODY, MUTED, MARGIN, CONTENT, copy.orderRef() + ": " + receipt.orderRef());
        sheet.rule();

        float half = (CONTENT - COLUMN_GAP) / 2;
        sheet.facts(half, copy.paidAt(), ReceiptFormat.paidAt(receipt.paidAt()),
                copy.method(), copy.methodName(receipt.provider(), receipt.instrument()));
        sheet.facts(half, copy.transactionId(receipt.provider()), orDash(receipt.providerTransactionId()), null, null);
        sheet.rule();

        seller(sheet, receipt.seller(), copy);
        sheet.gap(10);

        table(sheet, receipt, copy);
        summary(sheet, receipt, copy);

        sheet.rule();
        sheet.paragraph(sheet.regular, SMALL, MUTED, MARGIN, CONTENT, copy.note());
    }

    private static void seller(Sheet sheet, Seller seller, ReceiptCopy copy) throws IOException {
        sheet.paragraph(sheet.regular, SMALL, MUTED, MARGIN, CONTENT, copy.provider());
        if (!seller.isSet()) {
            sheet.paragraph(sheet.regular, BODY, INK, MARGIN, CONTENT, copy.providerUnset());
            return;
        }
        sheet.paragraph(sheet.bold, BODY, INK, MARGIN, CONTENT, seller.legalName());
        if (seller.taxCode() != null) {
            sheet.paragraph(sheet.regular, BODY, INK, MARGIN, CONTENT, copy.taxCode() + ": " + seller.taxCode());
        }
        if (seller.address() != null) {
            sheet.paragraph(sheet.regular, BODY, INK, MARGIN, CONTENT, seller.address());
        }
        if (seller.email() != null) {
            sheet.paragraph(sheet.regular, BODY, INK, MARGIN, CONTENT, seller.email());
        }
    }

    private static void table(Sheet sheet, Receipt receipt, ReceiptCopy copy) throws IOException {
        float quantityRight = MARGIN + NAME_WIDTH + QUANTITY_WIDTH;
        float unitPriceRight = quantityRight + UNIT_PRICE_WIDTH;
        float amountRight = MARGIN + CONTENT;

        sheet.ensure(SMALL * LEADING + 6);
        float header = sheet.y - SMALL;
        sheet.text(sheet.bold, SMALL, MUTED, MARGIN, header, copy.product());
        sheet.textRight(sheet.bold, SMALL, MUTED, quantityRight, header, copy.quantity());
        sheet.textRight(sheet.bold, SMALL, MUTED, unitPriceRight, header, copy.unitPrice());
        sheet.textRight(sheet.bold, SMALL, MUTED, amountRight, header, copy.amount());
        sheet.y -= SMALL * LEADING + 4;
        sheet.rule();

        for (Line line : receipt.lines()) {
            List<String> name = sheet.wrap(sheet.regular, BODY, NAME_WIDTH - 12, line.description());
            sheet.ensure(Math.max(1, name.size()) * BODY * LEADING + 6);
            float baseline = sheet.y - BODY;
            for (int i = 0; i < name.size(); i++) {
                sheet.text(sheet.regular, BODY, INK, MARGIN, baseline - i * BODY * LEADING, name.get(i));
            }
            sheet.textRight(sheet.regular, BODY, INK, quantityRight, baseline, Integer.toString(line.quantity()));
            sheet.textRight(sheet.regular, BODY, INK, unitPriceRight, baseline, ReceiptFormat.vnd(line.unitPriceVnd()));
            sheet.textRight(sheet.bold, BODY, INK, amountRight, baseline, ReceiptFormat.vnd(line.amountVnd()));
            sheet.y -= Math.max(1, name.size()) * BODY * LEADING + 6;
            sheet.rule();
        }
    }

    private static void summary(Sheet sheet, Receipt receipt, ReceiptCopy copy) throws IOException {
        float labelX = MARGIN + NAME_WIDTH;
        float right = MARGIN + CONTENT;
        sheet.summaryRow(sheet.regular, BODY, labelX, right, copy.subtotal(), ReceiptFormat.vnd(receipt.subtotalVnd()));
        sheet.summaryRow(sheet.regular, BODY, labelX, right, copy.vat(receipt.vatRateBps()),
                ReceiptFormat.vnd(receipt.vatAmountVnd()));
        sheet.summaryRow(sheet.bold, 12, labelX, right, copy.total(), ReceiptFormat.vnd(receipt.totalVnd()));
        sheet.gap(4);
        sheet.paragraph(sheet.regular, BODY, INK, MARGIN, CONTENT, copy.inWords() + ": " + receipt.totalInWords());
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static byte[] font(String path) {
        try (InputStream in = ReceiptPdf.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing receipt font " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read receipt font " + path, e);
        }
    }

    /** One font and the answer to which code points it can draw. */
    private static final class Face {

        final PDFont font;
        private final Map<Integer, Boolean> drawable = new HashMap<>();

        Face(PDFont font) {
            this.font = font;
        }

        /** NFC-normalised, whitespace as plain spaces, each code point the font lacks as one {@code ?}. */
        String clean(String raw) throws IOException {
            String text = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC);
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    out.append(' ');
                } else {
                    out.append(canDraw(cp) ? Character.toString(cp) : "?");
                }
            }
            return out.toString();
        }

        float width(String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        private boolean canDraw(int cp) throws IOException {
            Boolean known = drawable.get(cp);
            if (known != null) {
                return known;
            }
            boolean ok;
            try {
                font.encode(Character.toString(cp));
                ok = true;
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            drawable.put(cp, ok);
            return ok;
        }
    }

    /** The pages of one document: the current page's stream and the top of the next free space. */
    private static final class Sheet implements AutoCloseable {

        final Face regular;
        final Face bold;
        float y;
        private final PDDocument document;
        private final List<TrueTypeFont> sources = new ArrayList<>();
        private PDPageContentStream stream;

        Sheet(PDDocument document) throws IOException {
            this.document = document;
            this.regular = new Face(embed(REGULAR));
            this.bold = new Face(embed(BOLD));
            newPage();
        }

        /**
         * Without GSUB, showText writes each character's own glyph; with it PDFBox fuses pairs such as fi and ff
         * into one ligature glyph that the text layer then loses. The source stays open until the subset is
         * written on save.
         */
        private PDType0Font embed(byte[] bytes) throws IOException {
            TrueTypeFont source = new TTFParser().parse(new RandomAccessReadBuffer(bytes));
            sources.add(source);
            source.setEnableGsub(false);
            return PDType0Font.load(document, source, true);
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        /** Continues on a new page when {@code height} more points would pass the bottom margin. */
        void ensure(float height) throws IOException {
            if (y - height < MARGIN) {
                newPage();
            }
        }

        void gap(float height) {
            y -= height;
        }

        void rule() throws IOException {
            ensure(8);
            stream.setStrokingColor(RULE);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, y - 4);
            stream.lineTo(MARGIN + CONTENT, y - 4);
            stream.stroke();
            y -= 8;
        }

        void text(Face face, float size, Color color, float x, float baseline, String text) throws IOException {
            String drawn = face.clean(text);
            if (drawn.isEmpty()) {
                return;
            }
            stream.beginText();
            stream.setFont(face.font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline);
            stream.showText(drawn);
            stream.endText();
        }

        void textRight(Face face, float size, Color color, float right, float baseline, String text)
                throws IOException {
            String drawn = face.clean(text);
            text(face, size, color, right - face.width(drawn, size), baseline, drawn);
        }

        void paragraph(Face face, float size, Color color, float x, float width, String text) throws IOException {
            for (String line : wrap(face, size, width, text)) {
                ensure(size * LEADING);
                text(face, size, color, x, y - size, line);
                y -= size * LEADING;
            }
        }

        /** A label above a value in two columns; the right pair is optional. */
        void facts(float columnWidth, String leftLabel, String leftValue, String rightLabel, String rightValue)
                throws IOException {
            List<String> left = wrap(regular, BODY, columnWidth, leftValue);
            List<String> right = rightValue == null ? List.of() : wrap(regular, BODY, columnWidth, rightValue);
            float height = SMALL * LEADING + Math.max(1, Math.max(left.size(), right.size())) * BODY * LEADING + 4;
            ensure(height);
            float rightX = MARGIN + columnWidth + COLUMN_GAP;
            text(regular, SMALL, MUTED, MARGIN, y - SMALL, leftLabel);
            if (rightLabel != null) {
                text(regular, SMALL, MUTED, rightX, y - SMALL, rightLabel);
            }
            float valueTop = y - SMALL * LEADING;
            for (int i = 0; i < left.size(); i++) {
                text(regular, BODY, INK, MARGIN, valueTop - BODY - i * BODY * LEADING, left.get(i));
            }
            for (int i = 0; i < right.size(); i++) {
                text(regular, BODY, INK, rightX, valueTop - BODY - i * BODY * LEADING, right.get(i));
            }
            y -= height;
        }

        void summaryRow(Face face, float size, float labelX, float right, String label, String value)
                throws IOException {
            ensure(size * LEADING + 2);
            text(face, size, INK, labelX, y - size, label);
            textRight(face, size, INK, right, y - size, value);
            y -= size * LEADING + 2;
        }

        /** Breaks {@code raw} into lines no wider than {@code width}; a word wider than a line is cut. */
        List<String> wrap(Face face, float size, float width, String raw) throws IOException {
            String text = face.clean(raw).trim();
            List<String> lines = new ArrayList<>();
            String line = "";
            for (String word : text.split(" +")) {
                if (word.isEmpty()) {
                    continue;
                }
                String joined = line.isEmpty() ? word : line + " " + word;
                if (face.width(joined, size) <= width) {
                    line = joined;
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line);
                    line = "";
                }
                String rest = word;
                while (face.width(rest, size) > width) {
                    int cut = fitting(face, size, width, rest);
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line = rest;
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
            return lines;
        }

        /** The length in chars of the longest prefix, ending between code points, that fits {@code width}. */
        private int fitting(Face face, float size, float width, String word) throws IOException {
            int cut = 0;
            for (int i = 0; i < word.length(); ) {
                int next = i + Character.charCount(word.codePointAt(i));
                if (face.width(word.substring(0, next), size) > width) {
                    break;
                }
                cut = next;
                i = next;
            }
            return Math.max(cut, Character.charCount(word.codePointAt(0)));
        }

        void finish() throws IOException {
            stream.close();
            stream = null;
        }

        @Override
        public void close() throws IOException {
            try {
                if (stream != null) {
                    stream.close();
                }
            } finally {
                for (TrueTypeFont source : sources) {
                    source.close();
                }
            }
        }
    }
}
