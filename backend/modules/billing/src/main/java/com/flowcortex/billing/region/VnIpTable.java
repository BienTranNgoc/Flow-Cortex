package com.flowcortex.billing.region;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vietnam-registered address ranges as sorted, merged, non-overlapping inclusive ranges, queried by
 * binary search. Immutable, so a refresh swaps a whole table.
 */
public final class VnIpTable {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One delegated statistics file the table was built from. */
    public record Source(String registry, String url, String serial, String endDate, String md5) { }

    /** An IPv4 registry row: a start and a host count, which need not be a CIDR block. */
    public record V4Record(long start, long count) { }

    /** An IPv6 registry row: a start and a prefix length. */
    public record V6Record(BigInteger start, int prefixLength) { }

    private final Instant generatedAt;
    private final List<Source> sources;
    private final int ipv4RecordCount;
    private final int ipv6RecordCount;
    private final long[] v4Starts;
    private final long[] v4Ends;
    private final BigInteger[] v6Starts;
    private final BigInteger[] v6Ends;

    private VnIpTable(Instant generatedAt, List<Source> sources, int ipv4RecordCount, int ipv6RecordCount,
            List<long[]> v4, List<BigInteger[]> v6) {
        this.generatedAt = generatedAt;
        this.sources = List.copyOf(sources);
        this.ipv4RecordCount = ipv4RecordCount;
        this.ipv6RecordCount = ipv6RecordCount;
        List<long[]> mergedV4 = mergeV4(v4);
        this.v4Starts = mergedV4.stream().mapToLong(r -> r[0]).toArray();
        this.v4Ends = mergedV4.stream().mapToLong(r -> r[1]).toArray();
        List<BigInteger[]> mergedV6 = mergeV6(v6);
        this.v6Starts = mergedV6.stream().map(r -> r[0]).toArray(BigInteger[]::new);
        this.v6Ends = mergedV6.stream().map(r -> r[1]).toArray(BigInteger[]::new);
    }

    /** Builds a table from raw registry records; record counts are the raw rows, before merging. */
    public static VnIpTable build(List<V4Record> ipv4, List<V6Record> ipv6, Instant generatedAt, List<Source> sources) {
        List<long[]> v4 = new ArrayList<>(ipv4.size());
        for (V4Record record : ipv4) {
            v4.add(new long[] {record.start(), record.start() + record.count() - 1});
        }
        List<BigInteger[]> v6 = new ArrayList<>(ipv6.size());
        for (V6Record record : ipv6) {
            BigInteger size = BigInteger.ONE.shiftLeft(128 - record.prefixLength());
            v6.add(new BigInteger[] {record.start(), record.start().add(size).subtract(BigInteger.ONE)});
        }
        return new VnIpTable(generatedAt, sources, ipv4.size(), ipv6.size(), v4, v6);
    }

    public Instant generatedAt() {
        return generatedAt;
    }

    public List<Source> sources() {
        return sources;
    }

    /** Raw VN IPv4 rows before merging; what the refresh sanity bounds compare. */
    public int ipv4RecordCount() {
        return ipv4RecordCount;
    }

    public int ipv6RecordCount() {
        return ipv6RecordCount;
    }

    public int ipv4RangeCount() {
        return v4Starts.length;
    }

    public int ipv6RangeCount() {
        return v6Starts.length;
    }

    /**
     * Where the registries place an address: {@link RegionVerdict#VN}, {@link RegionVerdict#FOREIGN}, or
     * {@link RegionVerdict#UNKNOWN} when it cannot be placed (no table, no address, unparseable, or not
     * reachable from the internet).
     */
    public static RegionVerdict verdict(String ip, VnIpTable table) {
        if (table == null) {
            return RegionVerdict.UNKNOWN;
        }
        Optional<IpAddress> parsed = IpAddress.parse(ip);
        if (parsed.isEmpty() || parsed.get().isNonPublic()) {
            return RegionVerdict.UNKNOWN;
        }
        IpAddress address = parsed.get();
        boolean found = address.ipv4()
                ? containsV4(table.v4Starts, table.v4Ends, address.v4())
                : containsV6(table.v6Starts, table.v6Ends, address.v6());
        return found ? RegionVerdict.VN : RegionVerdict.FOREIGN;
    }

    private static boolean containsV4(long[] starts, long[] ends, long value) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (value < starts[mid]) {
                hi = mid - 1;
            } else if (value > ends[mid]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean containsV6(BigInteger[] starts, BigInteger[] ends, BigInteger value) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (value.compareTo(starts[mid]) < 0) {
                hi = mid - 1;
            } else if (value.compareTo(ends[mid]) > 0) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    // Adjacent ranges merge as well as overlapping ones.
    private static List<long[]> mergeV4(List<long[]> ranges) {
        List<long[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingLong(r -> r[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] range : sorted) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new long[] {range[0], range[1]});
            }
        }
        return merged;
    }

    private static List<BigInteger[]> mergeV6(List<BigInteger[]> ranges) {
        List<BigInteger[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(r -> r[0]));
        List<BigInteger[]> merged = new ArrayList<>();
        for (BigInteger[] range : sorted) {
            BigInteger[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0].compareTo(last[1].add(BigInteger.ONE)) <= 0) {
                last[1] = last[1].max(range[1]);
            } else {
                merged.add(new BigInteger[] {range[0], range[1]});
            }
        }
        return merged;
    }

    /** The committed JSON form: metadata first, then one range per line so a regeneration diffs by range. */
    public String serialise() {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"generatedAt\": ").append(quote(generatedAt.toString())).append(",\n");
        out.append("  \"sources\": ").append(json(sources)).append(",\n");
        out.append("  \"ipv4RecordCount\": ").append(ipv4RecordCount).append(",\n");
        out.append("  \"ipv6RecordCount\": ").append(ipv6RecordCount).append(",\n");
        out.append("  \"ipv4\": [\n");
        for (int i = 0; i < v4Starts.length; i++) {
            out.append("    [").append(quote(IpAddress.formatIpv4(v4Starts[i]))).append(',')
                    .append(quote(IpAddress.formatIpv4(v4Ends[i]))).append(']')
                    .append(i < v4Starts.length - 1 ? ",\n" : "\n");
        }
        out.append("  ],\n  \"ipv6\": [\n");
        for (int i = 0; i < v6Starts.length; i++) {
            out.append("    [").append(quote(IpAddress.formatIpv6(v6Starts[i]))).append(',')
                    .append(quote(IpAddress.formatIpv6(v6Ends[i]))).append(']')
                    .append(i < v6Starts.length - 1 ? ",\n" : "\n");
        }
        return out.append("  ]\n}\n").toString();
    }

    /** Reads a snapshot; throws {@link IllegalArgumentException} on any malformed field: a half-read table is worse than none. */
    public static VnIpTable deserialise(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("snapshot is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("snapshot is not an object");
        }
        Instant generatedAt = generatedAt(root.get("generatedAt"));
        List<Source> sources = sources(root.get("sources"));
        int v4Count = count(root.get("ipv4RecordCount"), "ipv4RecordCount");
        int v6Count = count(root.get("ipv6RecordCount"), "ipv6RecordCount");

        List<long[]> v4 = new ArrayList<>();
        JsonNode v4Node = list(root.get("ipv4"), "ipv4");
        for (int i = 0; i < v4Node.size(); i++) {
            String[] pair = pair(v4Node.get(i), "ipv4", i);
            long start = IpAddress.parseIpv4(pair[0]);
            long end = IpAddress.parseIpv4(pair[1]);
            if (start < 0 || end < 0 || start > end) {
                throw new IllegalArgumentException("snapshot ipv4[" + i + "] is not a valid range");
            }
            v4.add(new long[] {start, end});
        }
        List<BigInteger[]> v6 = new ArrayList<>();
        JsonNode v6Node = list(root.get("ipv6"), "ipv6");
        for (int i = 0; i < v6Node.size(); i++) {
            String[] pair = pair(v6Node.get(i), "ipv6", i);
            BigInteger start = IpAddress.parseIpv6(pair[0]);
            BigInteger end = IpAddress.parseIpv6(pair[1]);
            if (start == null || end == null || start.compareTo(end) > 0) {
                throw new IllegalArgumentException("snapshot ipv6[" + i + "] is not a valid range");
            }
            v6.add(new BigInteger[] {start, end});
        }
        return new VnIpTable(generatedAt, sources, v4Count, v6Count, v4, v6);
    }

    private static Instant generatedAt(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new IllegalArgumentException("snapshot metadata is malformed: generatedAt");
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("snapshot metadata is malformed: generatedAt", e);
        }
    }

    private static List<Source> sources(JsonNode node) {
        JsonNode array = list(node, "sources");
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            if (!item.isObject() || text(item, "registry", false) == null || text(item, "url", false) == null) {
                throw new IllegalArgumentException("snapshot sources[" + i + "] is malformed");
            }
            sources.add(new Source(text(item, "registry", false), text(item, "url", false), text(item, "serial", true),
                    text(item, "endDate", true), text(item, "md5", true)));
        }
        return sources;
    }

    private static String text(JsonNode object, String field, boolean nullable) {
        JsonNode value = object.get(field);
        if (value != null && value.isString()) {
            return value.asString();
        }
        if (nullable && (value == null || value.isNull())) {
            return null;
        }
        throw new IllegalArgumentException("snapshot field " + field + " is malformed");
    }

    private static int count(JsonNode node, String label) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0) {
            throw new IllegalArgumentException("snapshot metadata is malformed: " + label);
        }
        return node.asInt();
    }

    private static JsonNode list(JsonNode node, String label) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("snapshot " + label + " is not a list");
        }
        return node;
    }

    private static String[] pair(JsonNode node, String label, int index) {
        if (!node.isArray() || node.size() != 2 || !node.get(0).isString() || !node.get(1).isString()) {
            throw new IllegalArgumentException("snapshot " + label + "[" + index + "] is not a valid range");
        }
        return new String[] {node.get(0).asString(), node.get(1).asString()};
    }

    private static String quote(String value) {
        return MAPPER.writeValueAsString(value);
    }

    private static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @Override
    public String toString() {
        return "VnIpTable[ipv4Ranges=" + v4Starts.length + ", ipv6Ranges=" + v6Starts.length + ", generatedAt=" + generatedAt
                + ", sources=" + Arrays.toString(sources.stream().map(Source::registry).toArray()) + "]";
    }
}
