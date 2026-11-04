package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.infrastructure.persistence.DatabaseRoles;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publicador del Transactional Outbox (ADR-007), igual al de Identity y Catalog: lee los eventos pendientes con el
 * rol del publicador ({@code matching_outbox}, BYPASSRLS, con {@code SET LOCAL ROLE}), los publica en el topic con
 * el nombre del evento y la clave del agregado (la solicitud), y marca {@code published_at}. Si Kafka falla, suma un
 * intento y reintenta en la siguiente vuelta: ningún evento se pierde ni se salta el orden.
 */
@Component
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String publisherRole;
    private final int batchSize;

    public OutboxPublisher(TransactionTemplate transactions, JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            @Value("${quickpatch.outbox.publisher-role:}") String publisherRole,
            @Value("${quickpatch.outbox.batch-size:50}") int batchSize) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.publisherRole = DatabaseRoles.validate(publisherRole);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${quickpatch.outbox.poll-interval:PT1S}")
    public void run() {
        try {
            int published;
            do {
                published = publishPending();
            } while (published == batchSize);
        } catch (RuntimeException e) {
            LOG.error("Falló la publicación del Outbox.", e);
        }
    }

    /** Publica un lote de eventos pendientes; devuelve cuántos quedaron publicados. */
    public int publishPending() {
        var published = transactions.execute(status -> {
            DatabaseRoles.adopt(jdbc, publisherRole);
            var batch = jdbc.query("""
                    SELECT id, aggregate_id, event_type, payload::text AS payload, attempts
                      FROM outbox_events
                     WHERE published_at IS NULL
                     ORDER BY created_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new Pending(rs.getObject("id", UUID.class),
                    rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                    rs.getInt("attempts")), batchSize);

            var count = 0;
            for (var event : batch) {
                try {
                    send(event);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    jdbc.update("UPDATE outbox_events SET attempts = attempts + 1 WHERE id = ?", event.id());
                    LOG.warn("No se pudo publicar el evento {} (intento {}).", event.id(), event.attempts() + 1, e);
                    break;
                }
                jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", event.id());
                count++;
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private void send(Pending event) throws Exception {
        var record = new ProducerRecord<>(event.type(), event.aggregateId().toString(), event.payload());
        record.headers().add("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.type().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private record Pending(UUID id, UUID aggregateId, String type, String payload, int attempts) {
    }
}
