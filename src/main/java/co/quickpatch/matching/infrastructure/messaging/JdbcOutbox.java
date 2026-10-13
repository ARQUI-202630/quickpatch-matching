package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.domain.events.MatchingEvents;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guarda el evento en {@code outbox_events} (DD 5.15) dentro de la transacción del caso de uso, con el sobre
 * completo del contrato. El {@code id} de la fila es el {@code eventId}. La política RLS de inserción impide
 * guardar un evento de otro tenant que el de la sesión.
 */
@Component
public class JdbcOutbox implements MatchingPorts.Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public JdbcOutbox(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void enqueue(UUID tenantId, UUID aggregateId, String eventType, Object data, UUID correlationId) {
        var eventId = UUID.randomUUID();
        var occurredAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", MatchingEvents.VERSION);
        envelope.put("occurredAt", occurredAt);
        envelope.put("correlationId", correlationId);
        envelope.put("tenantId", tenantId);
        envelope.put("producer", MatchingEvents.PRODUCER);
        envelope.put("data", data);

        jdbc.update("""
                INSERT INTO outbox_events (id, tenant_id, aggregate_id, event_type, payload, created_at, attempts)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, 0)
                """, eventId, tenantId, aggregateId, eventType, json.writeValueAsString(envelope), occurredAt);
    }
}
