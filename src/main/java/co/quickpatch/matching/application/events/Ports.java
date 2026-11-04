package co.quickpatch.matching.application.events;

import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

/** Puertos de la capa de aplicación que implementa la infraestructura. */
public final class Ports {

    private Ports() {
    }

    /**
     * Ejecuta un trabajo en una transacción que primero fija el tenant de la sesión
     * ({@code set_config('app.current_tenant', ..., true)}), para que RLS aplique (ADR-005, DD 10.2).
     */
    public interface TenantTransaction {
        <T> T execute(UUID tenantId, Supplier<T> work);
    }

    /** Registro de eventos ya aplicados ({@code processed_events}, DD 5.16, RN-EV1). */
    public interface ProcessedEventStore {
        /** Registra el evento; devuelve {@code false} si ya estaba registrado (duplicado). */
        boolean register(UUID eventId, UUID tenantId, String eventType);
    }

    /**
     * Inicia el matching de una solicitud nueva (RF-09, RN-M1 a RN-M7). Se ejecuta dentro de la misma
     * transacción en que se registra el evento, así que el efecto y el registro se confirman juntos.
     */
    public interface MatchingStarter {
        void start(EventEnvelope<ServiceRequestCreated> event);
    }

    /** Estado del técnico que publica Identity (P-01): especialidad y verificación, con su versión (ADR-017). */
    public record TechnicianSnapshot(UUID tenantId, UUID technicianId, UUID specialtyId, String verificationStatus,
            OffsetDateTime updatedAt) {
    }

    /**
     * Proyección del técnico en {@code technician_availability} (DD 5.6). Crea la fila en {@code no_disponible}
     * si no existe; si existe, copia la especialidad y la verificación sin tocar la disponibilidad.
     */
    public interface TechnicianProjection {
        /** Devuelve {@code false} si ya había una versión igual o más reciente (el evento llegó tarde). */
        boolean apply(TechnicianSnapshot snapshot);
    }
}
