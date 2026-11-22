package com.flowcortex.billing.webhook;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The callback inbox. The partial unique index holds "one accepted row per provider and request id" for
 * concurrent deliveries; rejected rows are evidence and never conflict. No method logs a payload.
 */
@Repository
public class PaymentWebhookEventRepository {

    /** Whether the accepted row was stored or one already existed for the request id. */
    public enum InsertResult { ACCEPTED, DUPLICATE }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final NamedParameterJdbcTemplate jdbc;

    PaymentWebhookEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public InsertResult insertAccepted(String provider, String requestId, PaymentProviderEvent event, byte[] rawPayload,
            String sourceAddress, Instant receivedAt) {
        MapSqlParameterSource params = row(provider, requestId, null, rawPayload, sourceAddress, receivedAt)
                .addValue("event", json(event));
        int inserted = jdbc.update("INSERT INTO payment_webhook_event (id, provider, provider_request_id, disposition, "
                + "reason, event, raw_payload, source_address, received_at) "
                + "VALUES (:id, :provider, :requestId, 'accepted', :reason, CAST(:event AS jsonb), :raw, :source, :receivedAt) "
                + "ON CONFLICT (provider, provider_request_id) WHERE disposition = 'accepted' DO NOTHING", params);
        return inserted == 0 ? InsertResult.DUPLICATE : InsertResult.ACCEPTED;
    }

    /** Stores a refused callback with its raw bytes and no event; returns the row id. */
    public UUID insertRejected(String provider, String requestId, String reason, byte[] rawPayload,
            String sourceAddress, Instant receivedAt) {
        MapSqlParameterSource params = row(provider, requestId, reason, rawPayload, sourceAddress, receivedAt)
                .addValue("event", null);
        jdbc.update("INSERT INTO payment_webhook_event (id, provider, provider_request_id, disposition, "
                + "reason, event, raw_payload, source_address, received_at) "
                + "VALUES (:id, :provider, :requestId, 'rejected', :reason, CAST(:event AS jsonb), :raw, :source, :receivedAt)",
                params);
        return (UUID) params.getValue("id");
    }

    /** The normalised event of the accepted row for the request id, if one exists. */
    public Optional<PaymentProviderEvent> findAcceptedEvent(String provider, String requestId) {
        return jdbc.query("SELECT event::text FROM payment_webhook_event "
                + "WHERE provider = :provider AND provider_request_id = :requestId AND disposition = 'accepted'",
                new MapSqlParameterSource("provider", provider).addValue("requestId", requestId),
                (rs, row) -> read(rs.getString(1))).stream().findFirst();
    }

    /** The bytes stored for a row, exactly as received. */
    public Optional<byte[]> findRawPayload(UUID id) {
        return jdbc.query("SELECT raw_payload FROM payment_webhook_event WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, row) -> rs.getBytes(1)).stream().findFirst();
    }

    private static MapSqlParameterSource row(String provider, String requestId, String reason, byte[] rawPayload,
            String sourceAddress, Instant receivedAt) {
        return new MapSqlParameterSource("id", UUID.randomUUID())
                .addValue("provider", provider)
                .addValue("requestId", requestId)
                .addValue("reason", reason)
                .addValue("raw", rawPayload)
                .addValue("source", sourceAddress)
                .addValue("receivedAt", Timestamp.from(receivedAt));
    }

    private static String json(PaymentProviderEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("A provider event could not be written as JSON", e);
        }
    }

    private static PaymentProviderEvent read(String json) {
        try {
            return MAPPER.readValue(json, PaymentProviderEvent.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("A stored provider event could not be read", e);
        }
    }
}
