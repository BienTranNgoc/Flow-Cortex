package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentsRegionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    void startupWithAnOldSnapshotLogsNoError() {
        PaymentsRegionService service = new PaymentsRegionService(
                Clock.fixed(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC));

        List<ILoggingEvent> events = capture(service::load);

        assertThat(service.currentTable()).isNotNull();
        assertThat(events).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR);
        assertThat(events).extracting(ILoggingEvent::getLevel).contains(Level.WARN);
    }

    @Test
    void staleTableIsReportedAtErrorAfterAFailedRefresh() {
        PaymentsRegionService service = service();
        service.refresh(serving(300), NOW);

        List<ILoggingEvent> events = capture(() -> service.refresh(url -> {
            throw new IOException("network down");
        }, NOW.plus(Duration.ofDays(8))));

        assertThat(events).extracting(ILoggingEvent::getLevel).contains(Level.ERROR);
    }

    @Test
    void scheduledRefreshReturnsWhileTheDownloadIsStillRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger downloads = new AtomicInteger();
        RirFetch.Fetcher blocking = url -> {
            downloads.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("released");
        };
        PaymentsRegionService service = new PaymentsRegionService(Clock.fixed(NOW, ZoneOffset.UTC), () -> blocking);
        try {
            long begin = System.nanoTime();
            service.scheduledRefresh();
            assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(2));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            service.scheduledRefresh();
            release.countDown();
            Thread.sleep(200);
            assertThat(downloads.get()).isEqualTo(1);
        } finally {
            release.countDown();
            service.shutdown();
        }
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
