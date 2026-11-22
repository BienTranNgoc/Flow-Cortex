package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.DriveFileText;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the bytes of an untrusted Drive file into text within fixed bounds: 50,000 characters, PDF pages 1-200, a
 * 64 MB PDF memory budget, 20 MB of expanded DOCX XML, and 20 seconds of extraction checked between PDF pages and
 * DOCX paragraphs. A bound or an unreadable file is a warning on the result, never an exception, and nothing of the
 * file's content reaches a log line.
 */
@Component
public class DocumentText {

    /** What a MIME type is read as. */
    public enum Kind { GDOC, PDF, DOCX, TEXT, IMAGE, MEDIA, GSUITE, UNSUPPORTED }

    public static final String WARNING_TOO_LARGE = "tooLarge";
    public static final String WARNING_UNREADABLE = "unreadable";
    public static final String WARNING_ENCRYPTED = "encrypted";
    public static final String WARNING_MEDIA = "media";
    public static final String WARNING_UNSUPPORTED = "unsupported";
    public static final String WARNING_GOOGLE_FILE_TYPE = "googleFileType";

    static final int MAX_CHARS = 50_000;
    static final int MAX_PDF_PAGES = 200;
    static final long PDF_MEMORY_BYTES = 64L * 1024 * 1024;
    static final long DOCX_EXPANSION_CAP = 20L * 1024 * 1024;
    static final Duration EXTRACTION_DEADLINE = Duration.ofSeconds(20);

    private static final String DOCX_MAIN_PART = "word/document.xml";
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String GOOGLE_APPS = "application/vnd.google-apps.";
    private static final Logger LOG = LoggerFactory.getLogger(DocumentText.class);

    private final Clock clock;

    public DocumentText(Clock clock) {
        this.clock = clock;
    }

    /** How a file of {@code mimeType} is read. */
    public static Kind classify(String mimeType) {
        if (mimeType == null) {
            return Kind.UNSUPPORTED;
        }
        int parameters = mimeType.indexOf(';');
        String type = (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
        if ((GOOGLE_APPS + "document").equals(type)) {
            return Kind.GDOC;
        }
        if (type.startsWith(GOOGLE_APPS)) {
            return Kind.GSUITE;
        }
        if ("application/pdf".equals(type)) {
            return Kind.PDF;
        }
        if (DOCX_MIME.equals(type)) {
            return Kind.DOCX;
        }
        if (type.startsWith("text/")) {
            return Kind.TEXT;
        }
        if (type.startsWith("image/")) {
            return Kind.IMAGE;
        }
        if (type.startsWith("video/") || type.startsWith("audio/")) {
            return Kind.MEDIA;
        }
        return Kind.UNSUPPORTED;
    }

    /** The result for a file too large to download: no text, the type its MIME type implies. */
    public static DriveFileText tooLarge(String mimeType) {
        return new DriveFileText("", type(classify(mimeType)), false, false, WARNING_TOO_LARGE);
    }

    /** The text of {@code bytes} read as {@code mimeType}; {@code bytes} may be {@code null} for a type that is not read. */
    public DriveFileText fromBytes(byte[] bytes, String mimeType) {
        Kind kind = classify(mimeType);
        return switch (kind) {
            case PDF -> pdf(bytes);
            case DOCX -> docx(bytes);
            case TEXT -> capped(decode(bytes), "text");
            case GDOC -> fromExportedText(decode(bytes));
            case IMAGE -> new DriveFileText("", type(kind), false, true, null);
            case MEDIA -> empty(kind, WARNING_MEDIA);
            case GSUITE -> empty(kind, WARNING_GOOGLE_FILE_TYPE);
            case UNSUPPORTED -> empty(kind, WARNING_UNSUPPORTED);
        };
    }

    /** A Google Doc exported as plain text. */
    public DriveFileText fromExportedText(String text) {
        return capped(text, "gdoc");
    }

    private static String type(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static DriveFileText empty(Kind kind, String warning) {
        return new DriveFileText("", type(kind), false, false, warning);
    }

    private static DriveFileText unreadable(String type) {
        return new DriveFileText("", type, false, false, WARNING_UNREADABLE);
    }

    private static String decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static DriveFileText capped(String text, String type) {
        boolean truncated = text.length() > MAX_CHARS;
        return new DriveFileText(truncated ? cut(text, MAX_CHARS) : text, type, truncated, false, null);
    }

    private static String cut(CharSequence text, int max) {
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.subSequence(0, end).toString();
    }

    private DriveFileText pdf(byte[] bytes) {
        Instant deadline = clock.instant().plus(EXTRACTION_DEADLINE);
        try (PDDocument document = Loader.loadPDF(bytes, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(PDF_MEMORY_BYTES).streamCache)) {
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                return new DriveFileText("", "pdf", false, false, WARNING_ENCRYPTED);
            }
            CappedWriter out = new CappedWriter();
            DeadlineStripper stripper = new DeadlineStripper(deadline);
            try {
                stripper.writeText(document, out);
            } catch (DeadlineReached ex) {
                return unreadable("pdf");
            } catch (CapReached ex) {
                // the writer already holds the text up to the cap
            }
            String text = out.text().strip();
            return new DriveFileText(text, "pdf", out.truncated(), text.isEmpty(), null);
        } catch (InvalidPasswordException ex) {
            return new DriveFileText("", "pdf", false, false, WARNING_ENCRYPTED);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("A PDF could not be read: {}", ex.getClass().getSimpleName());
            return unreadable("pdf");
        }
    }

    private DriveFileText docx(byte[] bytes) {
        Instant deadline = clock.instant().plus(EXTRACTION_DEADLINE);
        try (InputStream part = ZipPart.open(bytes, DOCX_MAIN_PART, DOCX_EXPANSION_CAP)) {
            XMLStreamReader reader = secureFactory().createXMLStreamReader(part);
            try {
                return readParagraphs(reader, deadline);
            } finally {
                reader.close();
            }
        } catch (DeadlineReached ex) {
            return unreadable("docx");
        } catch (IOException | XMLStreamException | RuntimeException ex) {
            LOG.warn("A DOCX could not be read: {}", ex.getClass().getSimpleName());
            return unreadable("docx");
        }
    }

    private DriveFileText readParagraphs(XMLStreamReader reader, Instant deadline)
            throws XMLStreamException, DeadlineReached {
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        while (reader.hasNext() && text.length() <= MAX_CHARS) {
            switch (reader.next()) {
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                        throw new XMLStreamException("a document type declaration is not accepted");
                case XMLStreamConstants.START_ELEMENT -> {
                    if (WORD_NS.equals(reader.getNamespaceURI())) {
                        String name = reader.getLocalName();
                        if ("t".equals(name)) {
                            inText = true;
                        } else if ("tab".equals(name)) {
                            text.append('\t');
                        }
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (WORD_NS.equals(reader.getNamespaceURI())) {
                        String name = reader.getLocalName();
                        if ("t".equals(name)) {
                            inText = false;
                        } else if ("p".equals(name)) {
                            text.append('\n');
                            if (clock.instant().isAfter(deadline)) {
                                throw new DeadlineReached();
                            }
                        }
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (inText) {
                        text.append(reader.getText());
                    }
                }
                default -> { }
            }
        }
        boolean truncated = text.length() > MAX_CHARS;
        String result = (truncated ? cut(text, MAX_CHARS) : text.toString()).strip();
        return new DriveFileText(result, "docx", truncated, false, null);
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        return factory;
    }

    /** Thrown inside the extractors to stop at the character cap. */
    private static final class CapReached extends IOException {
        private static final long serialVersionUID = 1L;

        CapReached() {
            super("character cap reached");
        }
    }

    /** Thrown inside the extractors to stop at the time bound. */
    private static final class DeadlineReached extends IOException {
        private static final long serialVersionUID = 1L;

        DeadlineReached() {
            super("extraction deadline reached");
        }
    }

    /** Collects at most {@link #MAX_CHARS} characters and throws {@link CapReached} on the first one beyond. */
    private static final class CappedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            int room = MAX_CHARS - text.length();
            if (length <= room) {
                text.append(buffer, offset, length);
                return;
            }
            text.append(buffer, offset, room);
            truncated = true;
            if (text.length() > 0 && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                text.setLength(text.length() - 1);
            }
            throw new CapReached();
        }

        String text() {
            return text.toString();
        }

        boolean truncated() {
            return truncated;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** Reads pages 1-{@link #MAX_PDF_PAGES} and checks the clock before each page. */
    private final class DeadlineStripper extends PDFTextStripper {

        private final Instant deadline;

        DeadlineStripper(Instant deadline) throws IOException {
            this.deadline = deadline;
            setEndPage(MAX_PDF_PAGES);
            setLineSeparator("\n");
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            if (clock.instant().isAfter(deadline)) {
                throw new DeadlineReached();
            }
            super.startPage(page);
        }
    }

    /**
     * One entry of a zip held in memory, found through the central directory so no other entry is ever inflated,
     * read through a stream that fails the read that would pass {@code maxBytes} of expanded data.
     */
    static final class ZipPart {

        private static final int END_RECORD = 0x06054b50;
        private static final int CENTRAL_ENTRY = 0x02014b50;
        private static final int LOCAL_ENTRY = 0x04034b50;
        private static final int STORED = 0;
        private static final int DEFLATED = 8;

        private ZipPart() {
        }

        static InputStream open(byte[] zip, String name, long maxBytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
            int end = endRecord(buffer);
            int entries = buffer.getShort(end + 10) & 0xFFFF;
            int position = buffer.getInt(end + 16);
            byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < entries; i++) {
                requireRange(buffer, position, 46);
                if (buffer.getInt(position) != CENTRAL_ENTRY) {
                    throw new IOException("not a zip");
                }
                int method = buffer.getShort(position + 10) & 0xFFFF;
                int compressed = buffer.getInt(position + 20);
                int nameLength = buffer.getShort(position + 28) & 0xFFFF;
                int extraLength = buffer.getShort(position + 30) & 0xFFFF;
                int commentLength = buffer.getShort(position + 32) & 0xFFFF;
                int localOffset = buffer.getInt(position + 42);
                requireRange(buffer, position + 46, nameLength);
                if (matches(zip, position + 46, nameLength, wanted)) {
                    return entry(zip, buffer, method, compressed, localOffset, maxBytes);
                }
                position += 46 + nameLength + extraLength + commentLength;
            }
            throw new IOException("entry not found");
        }

        private static int endRecord(ByteBuffer buffer) throws IOException {
            int last = buffer.limit() - 22;
            for (int at = last; at >= 0 && at >= last - 0xFFFF; at--) {
                if (buffer.getInt(at) == END_RECORD) {
                    return at;
                }
            }
            throw new IOException("not a zip");
        }

        private static void requireRange(ByteBuffer buffer, int from, int length) throws IOException {
            if (from < 0 || length < 0 || (long) from + length > buffer.limit()) {
                throw new IOException("not a zip");
            }
        }

        private static boolean matches(byte[] zip, int from, int length, byte[] wanted) {
            if (length != wanted.length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (zip[from + i] != wanted[i]) {
                    return false;
                }
            }
            return true;
        }

        private static InputStream entry(byte[] zip, ByteBuffer buffer, int method, int compressed, int localOffset,
                long maxBytes) throws IOException {
            requireRange(buffer, localOffset, 30);
            if (buffer.getInt(localOffset) != LOCAL_ENTRY || compressed < 0) {
                throw new IOException("not a zip");
            }
            int dataStart = localOffset + 30 + (buffer.getShort(localOffset + 26) & 0xFFFF)
                    + (buffer.getShort(localOffset + 28) & 0xFFFF);
            requireRange(buffer, dataStart, compressed);
            InputStream raw = new ByteArrayInputStream(zip, dataStart, compressed);
            if (method == STORED) {
                return new Bounded(raw, null, maxBytes);
            }
            if (method == DEFLATED) {
                Inflater inflater = new Inflater(true);
                return new Bounded(new InflaterInputStream(raw, inflater), inflater, maxBytes);
            }
            throw new IOException("unsupported compression");
        }

        private static final class Bounded extends FilterInputStream {
            private final Inflater inflater;
            private final long max;
            private long total;

            Bounded(InputStream in, Inflater inflater, long max) {
                super(in);
                this.inflater = inflater;
                this.max = max;
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read == -1 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                long room = max - total;
                if (room == 0) {
                    if (in.read() == -1) {
                        return -1;
                    }
                    throw new IOException("expanded data passed the cap");
                }
                int read = in.read(buffer, offset, (int) Math.min(length, room));
                if (read > 0) {
                    total += read;
                }
                return read;
            }

            @Override
            public long skip(long count) throws IOException {
                byte[] sink = new byte[(int) Math.min(count, 8192)];
                int read = sink.length == 0 ? 0 : read(sink, 0, sink.length);
                return Math.max(read, 0);
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    if (inflater != null) {
                        inflater.end();
                    }
                }
            }
        }
    }
}
