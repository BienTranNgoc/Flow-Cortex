package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.payment.PaymentProviderEvent.Kind;
import com.flowcortex.billing.webhook.PaymentWebhookEventRepository;
import com.flowcortex.billing.webhook.PaymentWebhookEventRepository.InsertResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PaymentWebhookEventIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo_it";
    private static final String ORDER = "fc_0123456789abcdef0123456789abcdef";
    private static final String REQUEST = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00.123Z");
    private static final byte[] RAW = {'{', 0, (byte) 0xC3, 0x28, (byte) 0xFF, '}'};

    @Autowired
    private PaymentWebhookEventRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_webhook_event WHERE provider = ?", PROVIDER);
    }

    private static PaymentProviderEvent event() {
        return new PaymentProviderEvent("1", PROVIDER, Kind.INBOUND_PAYMENT_CALLBACK,
                new PaymentProviderEvent.Reference(ORDER, REQUEST, "4000000000001"), "0", "Successful.", 50_000L, NOW,
                "qr", NOW);
    }

    private InsertResult accepted(String requestId) {
        return repository.insertAccepted(PROVIDER, requestId, event(), RAW, "118.69.210.244", NOW);
    }

    private long rows(String disposition, String requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_webhook_event "
                + "WHERE provider = ? AND provider_request_id = ? AND disposition = ?", Long.class, PROVIDER,
                requestId, disposition);
    }

    @Test
    void aSecondAcceptedRowForTheSameRequestIdIsReportedAsADuplicate() {
        assertThat(accepted(REQUEST)).isEqualTo(InsertResult.ACCEPTED);
        assertThat(accepted(REQUEST)).isEqualTo(InsertResult.DUPLICATE);

        assertThat(rows("accepted", REQUEST)).isEqualTo(1);
    }

    @Test
    void twoDeliveriesReleasedTogetherLeaveOneAcceptedRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<InsertResult>> results = List.of(
                    pool.submit(() -> { go.await(); return accepted(REQUEST); }),
                    pool.submit(() -> { go.await(); return accepted(REQUEST); }));
            go.countDown();

            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(InsertResult.ACCEPTED, InsertResult.DUPLICATE);
            assertThat(rows("accepted", REQUEST)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRejectedRowDoesNotBlockALaterAcceptedRow() {
        repository.insertRejected(PROVIDER, REQUEST, "cross_check_unavailable", RAW, null, NOW);

        assertThat(accepted(REQUEST)).isEqualTo(InsertResult.ACCEPTED);
        assertThat(rows("rejected", REQUEST)).isEqualTo(1);
        assertThat(rows("accepted", REQUEST)).isEqualTo(1);
    }

    @Test
    void rejectedRowsForTheSameRequestIdAllPersist() {
        repository.insertRejected(PROVIDER, REQUEST, "signature_mismatch", RAW, null, NOW);
        repository.insertRejected(PROVIDER, REQUEST, "signature_mismatch", RAW, null, NOW);

        assertThat(rows("rejected", REQUEST)).isEqualTo(2);
    }

    @Test
    void theStoredEventReadsBackAsTheSameEvent() {
        accepted(REQUEST);

        assertThat(repository.findAcceptedEvent(PROVIDER, REQUEST)).contains(event());
    }

    @Test
    void aRawBodyWithANulByteAndInvalidUtf8IsStoredByteForByte() {
        UUID id = repository.insertRejected(PROVIDER, REQUEST, "unparseable_body", RAW, null, NOW);

        assertThat(repository.findRawPayload(id)).hasValueSatisfying(stored -> assertThat(stored).isEqualTo(RAW));
    }
}
