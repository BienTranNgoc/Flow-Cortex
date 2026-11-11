package com.flowcortex.billing.region;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reader for the registries' delegated statistics format. A header line
 * ({@code version|registry|serial|records|startdate|enddate|UTCoffset}) is followed by summary lines and
 * records ({@code registry|cc|type|start|value|date|status}). For IPv4 {@code value} is a host count and
 * the range need not be a CIDR block; for IPv6 it is a prefix length. Only {@code allocated} and
 * {@code assigned} rows name a holder.
 */
public final class RirDelegatedParser {

    private static final String COUNTRY = "VN";
    private static final Set<String> HELD_STATUSES = Set.of("allocated", "assigned");
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final Pattern MD5_TOKEN = Pattern.compile("\\b[0-9a-fA-F]{32}\\b");
    private static final long IPV4_SPACE = 1L << 32;

    private RirDelegatedParser() { }

    /**
     * The Vietnam rows of one file.
     *
     * @param registry  header registry, {@code null} when the file has no header line
     * @param malformed Vietnam rows that could not be read and were not added
     */
    public record Parsed(String registry, String serial, String endDate, List<VnIpTable.V4Record> ipv4,
            List<VnIpTable.V6Record> ipv6, int malformed) { }

    public static Parsed parse(String text) {
        List<VnIpTable.V4Record> ipv4 = new ArrayList<>();
        List<VnIpTable.V6Record> ipv6 = new ArrayList<>();
        String registry = null;
        String serial = null;
        String endDate = null;
        int malformed = 0;
        boolean headerSeen = false;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (!headerSeen && VERSION.matcher(fields[0]).matches()) {
                headerSeen = true;
                registry = field(fields, 1);
                serial = field(fields, 2);
                endDate = field(fields, 5);
                continue;
            }
            if ("summary".equals(field(fields, 5)) || fields.length < 2 || !COUNTRY.equalsIgnoreCase(fields[1])) {
                continue;
            }
            String type = field(fields, 2);
            if (!"ipv4".equals(type) && !"ipv6".equals(type)) {
                continue;
            }
            if (fields.length < 7) {
                malformed++;
                continue;
            }
            if (!HELD_STATUSES.contains(fields[6])) {
                continue;
            }
            if (!DIGITS.matcher(fields[4]).matches()) {
                malformed++;
                continue;
            }
            long amount = Long.parseLong(fields[4]);
            if ("ipv4".equals(type)) {
                long first = IpAddress.parseIpv4(fields[3]);
                if (first < 0 || amount < 1 || first + amount > IPV4_SPACE) {
                    malformed++;
                    continue;
                }
                ipv4.add(new VnIpTable.V4Record(first, amount));
            } else {
                BigInteger first = IpAddress.parseIpv6(fields[3]);
                if (first == null || amount > 128) {
                    malformed++;
                    continue;
                }
                ipv6.add(new VnIpTable.V6Record(first, (int) amount));
            }
        }
        return new Parsed(registry, serial, endDate, ipv4, ipv6, malformed);
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index] : null;
    }

    /**
     * Whether {@code content} matches the digest in a published {@code .md5} file. Registries word the
     * file differently ({@code MD5 (name) = hex} or {@code hex  name}), so the first 32-hex token is it.
     */
    public static boolean md5Matches(byte[] content, String md5File) {
        Matcher token = MD5_TOKEN.matcher(md5File);
        return token.find() && md5Hex(content).equals(token.group().toLowerCase(Locale.ROOT));
    }

    public static String md5Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by every JRE", e);
        }
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
