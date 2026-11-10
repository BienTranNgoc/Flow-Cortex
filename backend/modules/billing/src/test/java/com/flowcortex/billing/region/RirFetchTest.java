package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RirFetchTest {

    private static final Instant AT = Instant.parse("2026-11-08T00:00:00Z");

    static String delegated(String registry, int ipv4Rows) {
        StringBuilder out = new StringBuilder("2|" + registry + "|20261107|" + ipv4Rows + "|19830613|20261106|+1000\n");
        for (int i = 0; i < ipv4Rows; i++) {
            out.append(registry).append("|VN|ipv4|").append(IpAddress.formatIpv4(16_777_216L + i * 256L))
                    .append("|256|20100101|allocated\n");
        }
        return out.toString();
    }

    private static VnIpTable tableWith(int ipv4Records) {
        List<VnIpTable.V4Record> records = new ArrayList<>();
        for (int i = 0; i < ipv4Records; i++) {
            records.add(new VnIpTable.V4Record(16_777_216L + i * 256L, 256));
        }
        return VnIpTable.build(records, List.of(), AT, List.of());
    }

    /** Serves each file and digest from a map keyed by URL; a missing key fails the download. */
    private static final class FakeFetcher implements RirFetch.Fetcher {
        final Map<String, byte[]> responses = new HashMap<>();

        void serve(RirFetch.Source source, String content, String md5Wording) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            responses.put(source.url(), bytes);
            if (md5Wording != null) {
                responses.put(source.url() + ".md5",
                        md5Wording.replace("{hex}", RirDelegatedParser.md5Hex(bytes)).getBytes(StandardCharsets.UTF_8));
            }
        }

        void serveAll(int rowsPerRegistry) {
            for (RirFetch.Source source : RirFetch.RIR_SOURCES) {
                serve(source, delegated(source.registry(), rowsPerRegistry), "MD5 (x) = {hex}\n");
            }
        }

        @Override
        public byte[] get(String url) throws IOException {
            byte[] bytes = responses.get(url);
            if (bytes == null) {
                throw new IOException(url + " answered 404");
            }
            return bytes;
        }
    }

    @Test
    void sourcesAreTheFiveRegistriesWithApnicDigestRequired() {
        assertThat(RirFetch.RIR_SOURCES).extracting(RirFetch.Source::registry)
                .containsExactly("apnic", "arin", "ripencc", "lacnic", "afrinic");
        assertThat(RirFetch.RIR_SOURCES).filteredOn(s -> s.md5() == RirFetch.Md5.REQUIRED)
                .extracting(RirFetch.Source::registry).containsExactly("apnic");
    }

    @Test
    void goodDownloadsBuildATableWithBothMd5Wordings() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        List<RirFetch.Source> sources = RirFetch.RIR_SOURCES;
        for (int i = 0; i < sources.size(); i++) {
            fetcher.serve(sources.get(i), delegated(sources.get(i).registry(), 300),
                    i % 2 == 0 ? "MD5 (x) = {hex}\n" : "{hex}  x\n");
        }

        VnIpTable table = RirFetch.buildTable(RirFetch.download(fetcher, sources), AT);

        assertThat(table.ipv4RecordCount()).isEqualTo(1500);
        assertThat(table.sources()).hasSize(5);
        assertThat(table.generatedAt()).isEqualTo(AT);
    }

    @Test
    void optionalDigestMayBeMissingButApnicMayNot() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        for (RirFetch.Source source : RirFetch.RIR_SOURCES) {
            fetcher.serve(source, delegated(source.registry(), 10), source.registry().equals("apnic") ? "{hex}\n" : null);
        }
        assertThat(RirFetch.buildTable(RirFetch.download(fetcher, RirFetch.RIR_SOURCES), AT).ipv4RecordCount())
                .isEqualTo(50);

        fetcher.responses.remove(RirFetch.RIR_SOURCES.get(0).url() + ".md5");
        assertThatThrownBy(() -> RirFetch.download(fetcher, RirFetch.RIR_SOURCES)).isInstanceOf(IOException.class);
    }

    @Test
    void digestMismatchIsRefused() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.serveAll(10);
        fetcher.responses.put(RirFetch.RIR_SOURCES.get(1).url() + ".md5", "0".repeat(32).getBytes(StandardCharsets.UTF_8));
        List<RirFetch.Download> downloads = RirFetch.download(fetcher, RirFetch.RIR_SOURCES);

        assertThatThrownBy(() -> RirFetch.buildTable(downloads, AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arin");
    }

    @Test
    void missingRequiredDigestOnADownloadIsRefused() {
        RirFetch.Source apnic = RirFetch.RIR_SOURCES.get(0);
        RirFetch.Download download = new RirFetch.Download(apnic, delegated("apnic", 5).getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> RirFetch.buildTable(List.of(download), AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("md5");
    }

    @Test
    void fileWithoutHeaderIsRefused() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.serveAll(10);
        fetcher.serve(RirFetch.RIR_SOURCES.get(2), "ripencc|VN|ipv4|14.0.0.0|256|20100101|allocated\n", "{hex}\n");
        List<RirFetch.Download> downloads = RirFetch.download(fetcher, RirFetch.RIR_SOURCES);

        assertThatThrownBy(() -> RirFetch.buildTable(downloads, AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
    }

    @Test
    void failingDownloadPropagates() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.serveAll(10);
        fetcher.responses.remove(RirFetch.RIR_SOURCES.get(3).url());

        assertThatThrownBy(() -> RirFetch.download(fetcher, RirFetch.RIR_SOURCES))
                .isInstanceOf(IOException.class).hasMessageContaining("lacnic");
    }

    @Test
    void sanityBoundsOnTheMinimumRecordCount() {
        assertThat(RirFetch.sanityRejection(tableWith(999), null)).isNotNull();
        assertThat(RirFetch.sanityRejection(tableWith(1000), null)).isNull();
    }

    @Test
    void sanityBoundsOnTheChangeFromTheCurrentTable() {
        VnIpTable current = tableWith(2000);

        assertThat(RirFetch.sanityRejection(tableWith(2600), current)).isNull();
        assertThat(RirFetch.sanityRejection(tableWith(1400), current)).isNull();
        assertThat(RirFetch.sanityRejection(tableWith(2601), current)).isNotNull();
        assertThat(RirFetch.sanityRejection(tableWith(1399), current)).isNotNull();
    }
}
