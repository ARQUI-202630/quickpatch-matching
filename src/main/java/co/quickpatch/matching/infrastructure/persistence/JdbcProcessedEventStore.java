package co.quickpatch.matching.infrastructure.persistence;

import co.quickpatch.matching.application.events.Ports;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code processed_events} (DD 5.16). La clave primaria sobre {@code event_id} decide si el evento es nuevo:
 * {@code ON CONFLICT DO NOTHING} inserta cero filas cuando ya estaba registrado.
 */
@Component
public class JdbcProcessedEventStore implements Ports.ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean register(UUID eventId, UUID tenantId, String eventType) {
        int rows = jdbc.update("""
                INSERT INTO processed_events (event_id, tenant_id, event_type, processed_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, tenantId, eventType);
        return rows == 1;
    }
}
