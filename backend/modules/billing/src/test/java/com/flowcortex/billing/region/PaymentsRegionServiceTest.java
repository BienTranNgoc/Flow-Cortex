package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentsRegionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private static PaymentsRegionService service() {
        return new PaymentsRegionService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RirFetch.Fetcher serving(int rowsPerRegistry) {
        Map<String, byte[]> responses = new HashMap<>();
        for (RirFetch.Source source : RirFetch.RIR_SOURCES) {
            byte[] content = RirFetchTest.delegated(source.registry(), rowsPerRegistry).getBytes(StandardCharsets.UTF_8);
            responses.put(source.url(), content);
            responses.put(source.url() + ".md5", RirDelegatedParser.md5Hex(content).getBytes(StandardCharsets.UTF_8));
        }
        return url -> {
            byte[] bytes = responses.get(url);
            if (bytes == null) {
                throw new IOException(url + " answered 404");
            }
            return bytes;
        };
    }

    @Test
    void committedSnapshotPlacesCallers() {
        PaymentsRegionService service = service();
        service.load();

        VnIpTable table = service.currentTable();
        assertThat(table).isNotNull();
        assertThat(table.ipv4RecordCount()).isGreaterThanOrEqualTo(1000);
        assertThat(table.sources()).extracting(VnIpTable.Source::registry)
                .containsExactly("apnic", "arin", "ripencc", "lacnic", "afrinic");
        assertThat(service.verdictFor("14.160.0.1")).isEqualTo(RegionVerdict.VN);
        assertThat(service.verdictFor("113.160.0.1")).isEqualTo(RegionVerdict.VN);
        assertThat(service.verdictFor("23.128.180.1")).isEqualTo(RegionVerdict.VN);
        assertThat(service.verdictFor("8.8.8.8")).isEqualTo(RegionVerdict.FOREIGN);
    }

    @Test
    void unreadableSnapshotLeavesCallersUnknownWithoutThrowing() {
        PaymentsRegionService service = service();
        String bad = "{\"generatedAt\":\"2026-11-01T00:00:00Z\",\"sources\":[],\"ipv4RecordCount\":1,"
                + "\"ipv6RecordCount\":0,\"ipv4\":[[\"14.0.0.9\",\"14.0.0.1\"]],\"ipv6\":[]}";

        service.install(bad);

        assertThat(service.currentTable()).isNull();
        assertThat(service.verdictFor("8.8.8.8")).isEqualTo(RegionVerdict.UNKNOWN);
    }

    @Test
    void refreshSwapsInAGoodCandidate() {
        PaymentsRegionService service = service();

        assertThat(service.refresh(serving(300), NOW)).isTrue();

        assertThat(service.currentTable().generatedAt()).isEqualTo(NOW);
        assertThat(service.currentTable().ipv4RecordCount()).isEqualTo(1500);
        assertThat(service.verdictFor("1.0.0.1")).isEqualTo(RegionVerdict.VN);
    }

    @Test
    void refreshKeepsTheTableOnADownloadFailure() {
        PaymentsRegionService service = service();
        service.refresh(serving(300), NOW);
        VnIpTable before = service.currentTable();

        boolean replaced = service.refresh(url -> {
            throw new IOException("network down");
        }, NOW.plusSeconds(60));

        assertThat(replaced).isFalse();
        assertThat(service.currentTable()).isSameAs(before);
    }

    @Test
    void refreshKeepsTheTableOnADigestMismatch() {
        PaymentsRegionService service = service();
        service.refresh(serving(300), NOW);
        VnIpTable before = service.currentTable();
        RirFetch.Fetcher good = serving(300);

        boolean replaced = service.refresh(
                url -> url.endsWith(".md5") ? "0".repeat(32).getBytes(StandardCharsets.UTF_8) : good.get(url),
                NOW.plusSeconds(60));

        assertThat(replaced).isFalse();
        assertThat(service.currentTable()).isSameAs(before);
    }

    @Test
    void refreshKeepsTheTableOnABoundsRejection() {
        PaymentsRegionService service = service();
        service.refresh(serving(300), NOW);
        VnIpTable before = service.currentTable();

        assertThat(service.refresh(serving(10), NOW.plusSeconds(60))).isFalse();
        assertThat(service.refresh(serving(500), NOW.plusSeconds(60))).isFalse();

        assertThat(service.currentTable()).isSameAs(before);
    }

    @Test
    void tableOlderThanSevenDaysIsStale() {
        PaymentsRegionService service = service();
        service.refresh(serving(300), NOW);

        assertThat(service.isStale(NOW.plus(Duration.ofDays(7)))).isFalse();
        assertThat(service.isStale(NOW.plus(Duration.ofDays(7)).plusSeconds(1))).isTrue();
        assertThat(service().isStale(NOW)).isFalse();
    }
}
