package co.quickpatch.matching.application.events;

import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.TechnicianRegistered;
import co.quickpatch.matching.domain.events.TechnicianVerificationChanged;
import java.util.Objects;

/**
 * Mantiene la proyección del técnico en {@code technician_availability} (P-01, DD 5.6) con los eventos
 * {@code technician.*} de Identity. Cada evento se aplica una sola vez (ADR-007) bajo su tenant, y uno con un
 * {@code updatedAt} anterior al ya aplicado se descarta (ADR-017): el orden de llegada entre los dos topics no
 * deja la copia atrás. Así un técnico suspendido o rechazado deja de ser candidato (RN-TP1, RN-TP2).
 */
public class TechnicianEventsHandler {

    /** Resultado de aplicar un evento. */
    public enum Outcome { APPLIED, DUPLICATE, STALE }

    private final Ports.TenantTransaction transaction;
    private final Ports.ProcessedEventStore processedEvents;
    private final Ports.TechnicianProjection projection;

    public TechnicianEventsHandler(Ports.TenantTransaction transaction, Ports.ProcessedEventStore processedEvents,
            Ports.TechnicianProjection projection) {
        this.transaction = Objects.requireNonNull(transaction);
        this.processedEvents = Objects.requireNonNull(processedEvents);
        this.projection = Objects.requireNonNull(projection);
    }

    public Outcome handleRegistered(EventEnvelope<TechnicianRegistered> event) {
        Objects.requireNonNull(event, "event");
        event.validate(TechnicianRegistered.TYPE, TechnicianRegistered.VERSION);
        var data = event.data();
        data.validate();
        return apply(event, new Ports.TechnicianSnapshot(event.tenantId(), data.technicianId(), data.specialtyId(),
                data.verificationStatus(), data.updatedAt()));
    }

    public Outcome handleVerificationChanged(EventEnvelope<TechnicianVerificationChanged> event) {
        Objects.requireNonNull(event, "event");
        event.validate(TechnicianVerificationChanged.TYPE, TechnicianVerificationChanged.VERSION);
        var data = event.data();
        data.validate();
        return apply(event, new Ports.TechnicianSnapshot(event.tenantId(), data.technicianId(), data.specialtyId(),
                data.verificationStatus(), data.updatedAt()));
    }

    private Outcome apply(EventEnvelope<?> event, Ports.TechnicianSnapshot snapshot) {
        return transaction.execute(event.tenantId(), () -> {
            if (!processedEvents.register(event.eventId(), event.tenantId(), event.eventType())) {
                return Outcome.DUPLICATE;
            }
            return projection.apply(snapshot) ? Outcome.APPLIED : Outcome.STALE;
        });
    }
}
