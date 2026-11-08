package com.flowcortex.billing.region;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Holds the table of Vietnamese address ranges and places callers against it. The committed snapshot
 * loads at startup and a timer rebuilds the table from the registries; a refresh that fails keeps the
 * table in force, so a request never waits on the network or loses its answer to a bad download.
 */
@Service
public class PaymentsRegionService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentsRegionService.class);
    private static final String SNAPSHOT = "billing/region/vn-ip-ranges.snapshot.json";
    private static final Duration STALE_AFTER = Duration.ofDays(7);

    private final Clock clock;
    private volatile VnIpTable table;

    PaymentsRegionService(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(SNAPSHOT).getInputStream()) {
            install(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.error("The committed VN address snapshot could not be read; every caller is unplaced until a refresh"
                    + " succeeds", e);
        }
        reportIfStale(clock.instant());
    }

    /** Installs a snapshot; a malformed one leaves no table, so every caller places as unknown. */
    void install(String snapshotJson) {
        try {
            table = VnIpTable.deserialise(snapshotJson);
        } catch (IllegalArgumentException e) {
            LOG.error("The committed VN address snapshot is malformed; every caller is unplaced until a refresh"
                    + " succeeds: {}", e.getMessage());
        }
    }

    VnIpTable currentTable() {
        return table;
    }

    /** Where the registries place {@code ip}; never throws. */
    public RegionVerdict verdictFor(String ip) {
        try {
            return VnIpTable.verdict(ip, table);
        } catch (RuntimeException e) {
            LOG.warn("region lookup failed, treating the caller as unplaced: {}", e.getMessage());
            return RegionVerdict.UNKNOWN;
        }
    }

    @Scheduled(fixedDelayString = "${flowcortex.billing.region-gate.refresh-interval:PT24H}",
            initialDelayString = "${flowcortex.billing.region-gate.refresh-initial-delay:PT10M}")
    void scheduledRefresh() {
        refresh(RirFetch.httpFetcher(), clock.instant());
    }

    /** Rebuilds the table and swaps it in when it passes the sanity bounds; returns whether it did. Never throws. */
    boolean refresh(RirFetch.Fetcher fetcher, Instant now) {
        try {
            VnIpTable candidate = RirFetch.buildTable(RirFetch.download(fetcher, RirFetch.RIR_SOURCES), now);
            String rejection = RirFetch.sanityRejection(candidate, table);
            if (rejection != null) {
                LOG.warn("VN address table refresh rejected, keeping the current one: {}", rejection);
                reportIfStale(now);
                return false;
            }
            table = candidate;
            LOG.info("VN address table refreshed: {} IPv4 and {} IPv6 records", candidate.ipv4RecordCount(),
                    candidate.ipv6RecordCount());
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("VN address table refresh failed, keeping the current one: {}", e.getMessage());
            reportIfStale(now);
            return false;
        }
    }

    /** {@code true} when a table is held and was built more than seven days before {@code now}. */
    boolean isStale(Instant now) {
        VnIpTable current = table;
        return current != null && Duration.between(current.generatedAt(), now).compareTo(STALE_AFTER) > 0;
    }

    private void reportIfStale(Instant now) {
        if (isStale(now)) {
            LOG.error("The VN address table was built {} and is over 7 days old; refreshes have not been succeeding.",
                    table.generatedAt());
        }
    }
}
