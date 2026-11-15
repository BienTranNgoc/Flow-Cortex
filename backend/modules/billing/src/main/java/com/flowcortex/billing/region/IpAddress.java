package com.flowcortex.billing.region;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A parsed IP literal. The parser is strict and never resolves a name, so a hostname, a zone-scoped
 * address or any malformed text is simply not an address.
 *
 * @param ipv4 {@code true} when the address is IPv4 (an IPv4-mapped IPv6 address collapses to it)
 * @param v4   the IPv4 value, meaningful when {@code ipv4}
 * @param v6   the IPv6 value, meaningful when not {@code ipv4}
 */
public record IpAddress(boolean ipv4, long v4, BigInteger v6) {

    private static final BigInteger MAPPED_IPV4_PREFIX = BigInteger.valueOf(0xffffL);
    private static final BigInteger LOW_32_BITS = BigInteger.valueOf(0xffffffffL);

    // Not reachable from the public internet, so a registry says nothing about where the caller is.
    // Shared address space (100.64/10) is carrier NAT inside an ISP and belongs here too; 224/3 covers
    // multicast, the reserved 240/4 block and the broadcast address.
    private static final long[][] IPV4_NON_PUBLIC = {
        block("0.0.0.0", 8),
        block("10.0.0.0", 8),
        block("100.64.0.0", 10),
        block("127.0.0.0", 8),
        block("169.254.0.0", 16),
        block("172.16.0.0", 12),
        block("192.0.0.0", 24),
        block("192.0.2.0", 24),
        block("192.168.0.0", 16),
        block("198.18.0.0", 15),
        block("198.51.100.0", 24),
        block("203.0.113.0", 24),
        block("224.0.0.0", 3),
    };

    private static final BigInteger V6_GLOBAL_UNICAST_START = BigInteger.valueOf(0x2L).shiftLeft(124);
    private static final BigInteger V6_GLOBAL_UNICAST_END = BigInteger.valueOf(0x4L).shiftLeft(124).subtract(BigInteger.ONE);
    private static final BigInteger V6_DOCUMENTATION_START = BigInteger.valueOf(0x20010db8L).shiftLeft(96);
    private static final BigInteger V6_DOCUMENTATION_END = BigInteger.valueOf(0x20010db9L).shiftLeft(96).subtract(BigInteger.ONE);

    private static long[] block(String base, int prefix) {
        long start = parseIpv4(base);
        return new long[] {start, start + (1L << (32 - prefix)) - 1};
    }

    /** Parses an IPv4 or IPv6 literal; an IPv4-mapped IPv6 address is returned as the IPv4 address it carries. */
    public static Optional<IpAddress> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        long v4 = parseIpv4(trimmed);
        if (v4 >= 0) {
            return Optional.of(new IpAddress(true, v4, null));
        }
        BigInteger v6 = parseIpv6(trimmed);
        if (v6 == null) {
            return Optional.empty();
        }
        if (v6.shiftRight(32).equals(MAPPED_IPV4_PREFIX)) {
            return Optional.of(new IpAddress(true, v6.and(LOW_32_BITS).longValue(), null));
        }
        return Optional.of(new IpAddress(false, 0, v6));
    }

    /** {@code true} for an address a registry cannot place: private, loopback, link-local, multicast, documentation. */
    public boolean isNonPublic() {
        if (ipv4) {
            for (long[] range : IPV4_NON_PUBLIC) {
                if (v4 >= range[0] && v4 <= range[1]) {
                    return true;
                }
            }
            return false;
        }
        if (v6.compareTo(V6_GLOBAL_UNICAST_START) < 0 || v6.compareTo(V6_GLOBAL_UNICAST_END) > 0) {
            return true;
        }
        return v6.compareTo(V6_DOCUMENTATION_START) >= 0 && v6.compareTo(V6_DOCUMENTATION_END) <= 0;
    }

    /** Strict dotted-quad parse: four decimal parts of 0-255, no leading zeros. Returns -1 when not IPv4. */
    public static long parseIpv4(String text) {
        if (text == null) {
            return -1;
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return -1;
            }
            int octet = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return -1;
                }
                octet = octet * 10 + (c - '0');
            }
            if (octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    public static String formatIpv4(long value) {
        return ((value >> 24) & 0xff) + "." + ((value >> 16) & 0xff) + "." + ((value >> 8) & 0xff) + "." + (value & 0xff);
    }

    /**
     * Strict IPv6 parse (RFC 4291 text, optional dotted IPv4 tail). A zone-scoped address is refused:
     * it is link-local anyway. Returns {@code null} when the text is not IPv6.
     */
    public static BigInteger parseIpv6(String text) {
        if (text == null || text.isEmpty() || text.indexOf(':') < 0) {
            return null;
        }
        int gap = text.indexOf("::");
        if (gap != text.lastIndexOf("::")) {
            return null;
        }
        List<Integer> head = groups(gap < 0 ? text : text.substring(0, gap), gap < 0);
        List<Integer> tail = gap < 0 ? List.of() : groups(text.substring(gap + 2), true);
        if (head == null || tail == null) {
            return null;
        }
        int fill = 8 - head.size() - tail.size();
        if (gap < 0 ? fill != 0 : fill < 1) {
            return null;
        }
        List<Integer> all = new ArrayList<>(8);
        all.addAll(head);
        for (int i = 0; i < fill; i++) {
            all.add(0);
        }
        all.addAll(tail);
        BigInteger value = BigInteger.ZERO;
        for (int group : all) {
            value = value.shiftLeft(16).or(BigInteger.valueOf(group));
        }
        return value;
    }

    /** Hex groups of one side of {@code ::}; a dotted IPv4 tail is allowed only when {@code last} and ends the side. */
    private static List<Integer> groups(String part, boolean last) {
        List<Integer> groups = new ArrayList<>(8);
        if (part.isEmpty()) {
            return groups;
        }
        String[] items = part.split(":", -1);
        for (int i = 0; i < items.length; i++) {
            String item = items[i];
            if (item.indexOf('.') >= 0) {
                long v4 = parseIpv4(item);
                if (v4 < 0 || !last || i != items.length - 1) {
                    return null;
                }
                groups.add((int) (v4 >> 16));
                groups.add((int) (v4 & 0xffff));
            } else {
                if (item.isEmpty() || item.length() > 4) {
                    return null;
                }
                int group = 0;
                for (int j = 0; j < item.length(); j++) {
                    int digit = Character.digit(item.charAt(j), 16);
                    if (digit < 0 || item.charAt(j) > 'f') {
                        return null;
                    }
                    group = group * 16 + digit;
                }
                groups.add(group);
            }
        }
        return groups;
    }

    /** RFC 5952 text: lower case, the longest run of two or more zero groups written as {@code ::}. */
    public static String formatIpv6(BigInteger value) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = value.shiftRight((7 - i) * 16).intValue() & 0xffff;
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8;) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && groups[j] == 0) {
                j++;
            }
            if (j - i > bestLength) {
                bestStart = i;
                bestLength = j - i;
            }
            i = j;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }
}
