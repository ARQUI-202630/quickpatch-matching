package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sobre común de los eventos de QUICKPATCH (DD 8, {@code quickpatch-kafka/events/}).
 * {@code eventId} es la clave de idempotencia; {@code tenantId} fija el tenant de la transacción (ADR-005).
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        OffsetDateTime occurredAt,
        UUID correlationId,
        UUID tenantId,
        String producer,
        T data) {

    /** Valida los campos del sobre que el contrato marca como obligatorios y el tipo y la versión esperados. */
    public void validate(String expectedType, int expectedVersion) {
        if (eventId == null || occurredAt == null || correlationId == null || tenantId == null
                || producer == null || producer.isBlank() || data == null) {
            throw new InvalidEventException("Faltan campos obligatorios del sobre del evento.");
        }
        if (!expectedType.equals(eventType)) {
            throw new InvalidEventException("Tipo de evento inesperado: " + eventType);
        }
        if (eventVersion == null || eventVersion != expectedVersion) {
            throw new InvalidEventException("Versión de evento no soportada: " + eventVersion);
        }
    }
}
