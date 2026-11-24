package co.quickpatch.matching.application.common;

import java.util.UUID;

/** Registro de eventos ya aplicados ({@code processed_events}, DD 5.16, RN-EV1). */
public interface ProcessedEventStore {
    /** Registra el evento; devuelve {@code false} si ya estaba registrado (duplicado). */
    boolean register(UUID eventId, UUID tenantId, String eventType);
}
