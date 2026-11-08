package com.flowcortex.billing.region;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Downloads the five registries' delegated files and builds a candidate table, never trusting an unchecked one. */
public final class RirFetch {

    /** Whether a source's {@code .md5} must be obtained or is checked only when the registry answers. */
    public enum Md5 { REQUIRED, OPTIONAL }

    public record Source(String registry, String url, Md5 md5) { }

    /** A source's file, and its {@code .md5} text when one was obtained. */
    public record Download(Source source, byte[] content, String md5File) { }

    /** Fetches one URL; any failure, including a non-200 answer, is an {@link IOException}. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] get(String url) throws IOException;
    }

    /** APNIC holds almost every Vietnamese block; a few sit with ARIN and RIPE and would be missed otherwise. */
    public static final List<Source> RIR_SOURCES = List.of(
            new Source("apnic", "https://ftp.apnic.net/stats/apnic/delegated-apnic-latest", Md5.REQUIRED),
            new Source("arin", "https://ftp.arin.net/pub/stats/arin/delegated-arin-extended-latest", Md5.OPTIONAL),
            new Source("ripencc", "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest", Md5.OPTIONAL),
            new Source("lacnic", "https://ftp.lacnic.net/pub/stats/lacnic/delegated-lacnic-latest", Md5.OPTIONAL),
            new Source("afrinic", "https://ftp.afrinic.net/pub/stats/afrinic/delegated-afrinic-latest", Md5.OPTIONAL));

    public static final Duration FETCH_TIMEOUT = Duration.ofSeconds(120);
    public static final int MIN_VN_IPV4_RECORDS = 1_000;
    private static final int MAX_CHANGE_PERCENT = 30;

    private RirFetch() { }

    /**
     * A fetcher over {@link HttpClient}. The client sets no proxy, so it keeps the JVM default proxy
     * selector and reaches the registries through the egress proxy configured in {@code JAVA_TOOL_OPTIONS};
     * redirects stay off.
     */
    public static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(FETCH_TIMEOUT).GET().build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException(url + " answered " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(url + " was interrupted", e);
            }
        };
    }

    /** One source at a time: the files are several megabytes each and nothing waits on them. */
    public static List<Download> download(Fetcher fetcher, List<Source> sources) throws IOException {
        List<Download> downloads = new ArrayList<>(sources.size());
        for (Source source : sources) {
            byte[] content = fetcher.get(source.url());
            String md5File = null;
            try {
                md5File = RirDelegatedParser.utf8(fetcher.get(source.url() + ".md5"));
            } catch (IOException e) {
                if (source.md5() == Md5.REQUIRED) {
                    throw e;
                }
            }
            downloads.add(new Download(source, content, md5File));
        }
        return downloads;
    }

    /**
     * Builds a table from downloads; throws {@link IllegalArgumentException} when a digest is missing where
     * required or does not match, or a file is not a delegated file.
     */
    public static VnIpTable buildTable(List<Download> downloads, Instant generatedAt) {
        List<VnIpTable.V4Record> ipv4 = new ArrayList<>();
        List<VnIpTable.V6Record> ipv6 = new ArrayList<>();
        List<VnIpTable.Source> stamps = new ArrayList<>();
        for (Download download : downloads) {
            Source source = download.source();
            if (download.md5File() == null && source.md5() == Md5.REQUIRED) {
                throw new IllegalArgumentException(source.registry() + ": no .md5 to verify against");
            }
            if (download.md5File() != null && !RirDelegatedParser.md5Matches(download.content(), download.md5File())) {
                throw new IllegalArgumentException(source.registry() + ": content does not match its .md5");
            }
            RirDelegatedParser.Parsed parsed = RirDelegatedParser.parse(RirDelegatedParser.utf8(download.content()));
            if (parsed.registry() == null) {
                throw new IllegalArgumentException(source.registry() + ": no header line, not a delegated file");
            }
            ipv4.addAll(parsed.ipv4());
            ipv6.addAll(parsed.ipv6());
            stamps.add(new VnIpTable.Source(source.registry(), source.url(), parsed.serial(), parsed.endDate(),
                    RirDelegatedParser.md5Hex(download.content())));
        }
        return VnIpTable.build(ipv4, ipv6, generatedAt, stamps);
    }

    /**
     * Why a candidate must not replace the current table, or {@code null} when it may. A registry that
     * publishes a truncated file would otherwise turn every Vietnamese caller into a foreign one.
     */
    public static String sanityRejection(VnIpTable candidate, VnIpTable current) {
        int next = candidate.ipv4RecordCount();
        if (next < MIN_VN_IPV4_RECORDS) {
            return "only " + next + " VN IPv4 records (minimum " + MIN_VN_IPV4_RECORDS + ")";
        }
        if (current != null && current.ipv4RecordCount() > 0) {
            int previous = current.ipv4RecordCount();
            if ((long) Math.abs(next - previous) * 100 > (long) previous * MAX_CHANGE_PERCENT) {
                return "VN IPv4 records moved from " + previous + " to " + next + ", more than " + MAX_CHANGE_PERCENT + "%";
            }
        }
        return null;
    }
}
