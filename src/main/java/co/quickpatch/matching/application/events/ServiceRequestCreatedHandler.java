package co.quickpatch.matching.application.events;

import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;
import java.util.Objects;

/**
 * Aplica {@code service-request.created} una sola vez (ADR-007): en la misma transacción, bajo el tenant del
 * evento, registra el {@code eventId} en {@code processed_events} e inicia el matching. Si el evento ya estaba
 * registrado, no repite el efecto.
 */
public class ServiceRequestCreatedHandler {

    /** Resultado de aplicar un evento. */
    public enum Outcome { APPLIED, DUPLICATE }

    private final Ports.TenantTransaction transaction;
    private final Ports.ProcessedEventStore processedEvents;
    private final Ports.MatchingStarter matching;

    public ServiceRequestCreatedHandler(Ports.TenantTransaction transaction,
            Ports.ProcessedEventStore processedEvents, Ports.MatchingStarter matching) {
        this.transaction = Objects.requireNonNull(transaction);
        this.processedEvents = Objects.requireNonNull(processedEvents);
        this.matching = Objects.requireNonNull(matching);
    }

    public Outcome handle(EventEnvelope<ServiceRequestCreated> event) {
        Objects.requireNonNull(event, "event");
        event.validate(ServiceRequestCreated.TYPE, ServiceRequestCreated.VERSION);
        event.data().validate();

        return transaction.execute(event.tenantId(), () -> {
            if (!processedEvents.register(event.eventId(), event.tenantId(), event.eventType())) {
                return Outcome.DUPLICATE;
            }
            matching.start(event);
            return Outcome.APPLIED;
        });
    }
}
