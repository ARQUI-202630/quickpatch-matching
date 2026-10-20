package co.quickpatch.matching.application.events;

import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.ServiceRequestCompleted;
import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import co.quickpatch.matching.domain.technicians.Technicians;
import java.util.Objects;

/**
 * Aplica {@code service-request.completed} una sola vez (RN-M6; SCRUM-94), bajo el tenant del evento y en una sola
 * transacción con el registro del {@code eventId}: el técnico que aceptó la oferta de la solicitud, si sigue
 * {@code ocupado}, vuelve a {@code disponible} y se le ofrece la solicitud en espera más antigua para la que es candidato
 * (RN-M10, disparador {@code liberacion}). El técnico sale del registro de Matching y no del evento: Matching es quien lo
 * marcó ocupado. La búsqueda ya terminó en {@code asignado} y no cambia. La fila del técnico queda bloqueada hasta el final
 * de la transacción, igual que al aceptar una oferta o al cancelar.
 */
public class ServiceRequestCompletedHandler {

    /** Resultado de aplicar un evento. */
    public enum Outcome { RELEASED, DUPLICATE, NOT_BUSY, NOT_ASSIGNED }

    private final Ports.TenantTransaction transaction;
    private final Ports.ProcessedEventStore processedEvents;
    private final MatchingPorts.CancellationStore assignments;
    private final TechnicianPorts.AvailabilityStore availability;
    private final TechnicianPorts.SearchResumer resumer;

    public ServiceRequestCompletedHandler(Ports.TenantTransaction transaction, Ports.ProcessedEventStore processedEvents,
            MatchingPorts.CancellationStore assignments,
            TechnicianPorts.AvailabilityStore availability, TechnicianPorts.SearchResumer resumer) {
        this.transaction = Objects.requireNonNull(transaction);
        this.processedEvents = Objects.requireNonNull(processedEvents);
        this.assignments = Objects.requireNonNull(assignments);
        this.availability = Objects.requireNonNull(availability);
        this.resumer = Objects.requireNonNull(resumer);
    }

    public Outcome handle(EventEnvelope<ServiceRequestCompleted> event) {
        Objects.requireNonNull(event, "event");
        event.validate(ServiceRequestCompleted.TYPE, ServiceRequestCompleted.VERSION);
        event.data().validate();
        var serviceRequestId = event.data().serviceRequestId();

        return transaction.execute(event.tenantId(), () -> {
            if (!processedEvents.register(event.eventId(), event.tenantId(), event.eventType())) {
                return Outcome.DUPLICATE;
            }

            var technicianId = assignments.acceptedTechnician(serviceRequestId).orElse(null);
            if (technicianId == null) {
                return Outcome.NOT_ASSIGNED;
            }
            var technician = availability.findForUpdate(technicianId).orElse(null);
            if (technician == null || !Technicians.OCUPADO.equals(technician.status())) {
                return Outcome.NOT_BUSY;
            }

            availability.update(technicianId, Technicians.DISPONIBLE);
            resumer.technicianMayBeCandidate(event.tenantId(), technicianId, ResumeTrigger.RELEASE);
            return Outcome.RELEASED;
        });
    }
}
