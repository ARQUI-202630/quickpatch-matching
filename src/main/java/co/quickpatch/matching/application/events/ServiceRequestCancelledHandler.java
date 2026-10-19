package co.quickpatch.matching.application.events;

import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.ServiceRequestCancelled;
import co.quickpatch.matching.domain.matching.Matching.OfferResponse;
import co.quickpatch.matching.domain.matching.Matching.RequestStatus;
import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import co.quickpatch.matching.domain.technicians.Technicians;
import java.util.Objects;

/**
 * Aplica {@code service-request.cancelled} una sola vez (RN-M5, RN-M6; SCRUM-476), bajo el tenant del evento y en una
 * sola transacción con el registro del {@code eventId}:
 * <ul>
 *   <li>la oferta pendiente, si la hay, pasa a {@code cancelado} y el técnico deja de verla;</li>
 *   <li>la búsqueda pasa a {@code cancelado}, así que {@code MatchingEngine.offerNext} ya no la ofrece a nadie;</li>
 *   <li>si un técnico la había aceptado y está {@code ocupado}, vuelve a {@code disponible} y se le ofrece la solicitud
 *       en espera más antigua para la que es candidato (RN-M10, disparador {@code liberacion}).</li>
 * </ul>
 * Bloquea en el mismo orden que la aceptación de una oferta (oferta → técnico → solicitud), para que una aceptación y
 * una cancelación simultáneas no se bloqueen entre sí. Una solicitud que Matching no tiene (de otro tenant, o cuyo
 * {@code service-request.created} no llegó) se descarta (DD 10.3).
 */
public class ServiceRequestCancelledHandler {

    /** Resultado de aplicar un evento. */
    public enum Outcome { APPLIED, DUPLICATE, ALREADY_CANCELLED, NOT_FOUND }

    private final Ports.TenantTransaction transaction;
    private final Ports.ProcessedEventStore processedEvents;
    private final MatchingPorts.RequestStore requests;
    private final MatchingPorts.CancellationStore cancellations;
    private final MatchingPorts.OfferStore offers;
    private final TechnicianPorts.AvailabilityStore availability;
    private final TechnicianPorts.SearchResumer resumer;

    public ServiceRequestCancelledHandler(Ports.TenantTransaction transaction, Ports.ProcessedEventStore processedEvents,
            MatchingPorts.RequestStore requests, MatchingPorts.CancellationStore cancellations,
            MatchingPorts.OfferStore offers, TechnicianPorts.AvailabilityStore availability,
            TechnicianPorts.SearchResumer resumer) {
        this.transaction = Objects.requireNonNull(transaction);
        this.processedEvents = Objects.requireNonNull(processedEvents);
        this.requests = Objects.requireNonNull(requests);
        this.cancellations = Objects.requireNonNull(cancellations);
        this.offers = Objects.requireNonNull(offers);
        this.availability = Objects.requireNonNull(availability);
        this.resumer = Objects.requireNonNull(resumer);
    }

    public Outcome handle(EventEnvelope<ServiceRequestCancelled> event) {
        Objects.requireNonNull(event, "event");
        event.validate(ServiceRequestCancelled.TYPE, ServiceRequestCancelled.VERSION);
        event.data().validate();
        var serviceRequestId = event.data().serviceRequestId();

        return transaction.execute(event.tenantId(), () -> {
            if (!processedEvents.register(event.eventId(), event.tenantId(), event.eventType())) {
                return Outcome.DUPLICATE;
            }

            var pending = cancellations.pendingOfferForUpdate(serviceRequestId);
            var technicianId = cancellations.acceptedTechnician(serviceRequestId).orElse(null);
            var technician = technicianId == null ? null : availability.findForUpdate(technicianId).orElse(null);
            var request = requests.findForUpdate(serviceRequestId).orElse(null);
            if (request == null) {
                return Outcome.NOT_FOUND;
            }
            if (RequestStatus.CANCELLED.equals(request.status())) {
                return Outcome.ALREADY_CANCELLED;
            }

            pending.ifPresent(offer -> offers.respond(offer.attemptId(), OfferResponse.CANCELLED));
            requests.updateStatus(serviceRequestId, RequestStatus.CANCELLED);
            if (technician != null && Technicians.OCUPADO.equals(technician.status())) {
                availability.update(technicianId, Technicians.DISPONIBLE);
                resumer.technicianMayBeCandidate(event.tenantId(), technicianId, ResumeTrigger.RELEASE);
            }
            return Outcome.APPLIED;
        });
    }
}
