package co.quickpatch.matching.application.matching;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.MatchingEvents.NoTechnicianAvailable;
import co.quickpatch.matching.domain.events.MatchingEvents.OfferCreated;
import co.quickpatch.matching.domain.events.MatchingEvents.SearchResumed;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;
import co.quickpatch.matching.domain.matching.Matching.MatchingRequest;
import co.quickpatch.matching.domain.matching.Matching.OfferOutcome;
import co.quickpatch.matching.domain.matching.Matching.RequestStatus;
import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Motor de matching (RF-09, SCRUM-29; DD 7.5). Todas las operaciones corren dentro de la transacción por tenant de
 * quien las llama, así que la solicitud, la oferta y el evento en el Outbox se confirman juntos (ADR-007).
 *
 * <ul>
 *   <li>Solicitud nueva: la guarda en {@code buscando} y la ofrece al candidato más cercano (RN-M9).</li>
 *   <li>Oferta: una pendiente por solicitud y por técnico (RN-M1, RN-M7), que vence en {@code MATCHING_OFFER_TTL}
 *       (RN-M8), y {@code matching.offer-created}.</li>
 *   <li>Sin candidatos: la solicitud pasa a {@code en_espera} y se publica {@code matching.no-technician-available},
 *       sin relajar el criterio (RN-M4, AC9-E4).</li>
 *   <li>Reintento: cuando un técnico puede volver a ser candidato, la solicitud en espera más antigua que puede
 *       atender vuelve a {@code buscando} con {@code matching.search-resumed} y se le ofrece (RN-M10). Un barrido
 *       periódico cubre los casos que ningún disparador alcanzó.</li>
 * </ul>
 *
 * {@link #offerNext} es también el paso que sigue a un rechazo o a un vencimiento (RN-M3).
 */
public class MatchingEngine implements Ports.MatchingStarter, TechnicianPorts.SearchResumer {

    /** Candidatos que se leen por intento; si otra transacción toma uno en paralelo, se prueba el siguiente. */
    static final int CANDIDATES_PER_OFFER = 5;

    private final MatchingPorts.RequestStore requests;
    private final MatchingPorts.CandidateSearch candidates;
    private final MatchingPorts.AttemptStore attempts;
    private final MatchingPorts.Outbox outbox;
    private final MatchingPorts.CorrelationContext correlation;
    private final Duration offerTtl;

    public MatchingEngine(MatchingPorts.RequestStore requests, MatchingPorts.CandidateSearch candidates,
            MatchingPorts.AttemptStore attempts, MatchingPorts.Outbox outbox,
            MatchingPorts.CorrelationContext correlation, Duration offerTtl) {
        this.requests = Objects.requireNonNull(requests);
        this.candidates = Objects.requireNonNull(candidates);
        this.attempts = Objects.requireNonNull(attempts);
        this.outbox = Objects.requireNonNull(outbox);
        this.correlation = Objects.requireNonNull(correlation);
        if (offerTtl == null || offerTtl.isNegative() || offerTtl.isZero()) {
            throw new IllegalArgumentException("MATCHING_OFFER_TTL debe ser positivo.");
        }
        this.offerTtl = offerTtl;
    }

    @Override
    public void start(EventEnvelope<ServiceRequestCreated> event) {
        var data = event.data();
        var request = new MatchingRequest(data.serviceRequestId(), event.tenantId(), data.clientId(),
                data.categoryId(), data.location().latitude(), data.location().longitude(),
                RequestStatus.SEARCHING, data.createdAt(), data.description());
        if (requests.create(request)) {
            offerNext(data.serviceRequestId(), event.correlationId());
        }
    }

    /**
     * Ofrece la solicitud al siguiente candidato elegible (RN-M3, RN-M9) o la deja en espera si no queda ninguno.
     * No hace nada si la solicitud no está {@code buscando} o ya tiene una oferta pendiente (RN-M1).
     */
    public OfferOutcome offerNext(UUID serviceRequestId, UUID correlationId) {
        var request = requests.findForUpdate(serviceRequestId).orElse(null);
        if (request == null || !RequestStatus.SEARCHING.equals(request.status())
                || attempts.hasPendingOffer(serviceRequestId)) {
            return OfferOutcome.UNCHANGED;
        }

        for (var candidate : candidates.find(serviceRequestId, CANDIDATES_PER_OFFER)) {
            var offer = attempts.offer(request, candidate, offerTtl);
            if (offer.isPresent()) {
                var created = offer.get();
                outbox.enqueue(request.tenantId(), serviceRequestId, OfferCreated.TYPE,
                        new OfferCreated(serviceRequestId, created.attemptId(), created.technicianId(),
                                created.expiresAt()),
                        correlationId);
                return OfferOutcome.OFFERED;
            }
        }

        requests.updateStatus(serviceRequestId, RequestStatus.WAITING);
        outbox.enqueue(request.tenantId(), serviceRequestId, NoTechnicianAvailable.TYPE,
                new NoTechnicianAvailable(serviceRequestId, request.clientId(), attempts.count(serviceRequestId)),
                correlationId);
        return OfferOutcome.WAITING;
    }

    /** RN-M10: el técnico pasó a disponible, cambió su cobertura o fue aprobado. */
    @Override
    public void technicianMayBeCandidate(UUID tenantId, UUID technicianId, ResumeTrigger trigger) {
        requests.oldestWaitingFor(technicianId)
                .ifPresent(request -> resume(request, trigger, correlation.current()));
    }

    /**
     * Barrido de respaldo de RN-M10 en el tenant de la transacción: reanuda, de la más antigua a la más reciente,
     * las solicitudes en espera que ya tienen un candidato. Devuelve cuántas reanudó.
     */
    public int sweepWaiting(int limit) {
        var resumed = 0;
        for (var request : requests.waiting(limit)) {
            if (!candidates.find(request.serviceRequestId(), 1).isEmpty()) {
                resume(request, ResumeTrigger.SWEEP, UUID.randomUUID());
                resumed++;
            }
        }
        return resumed;
    }

    private void resume(MatchingRequest request, ResumeTrigger trigger, UUID correlationId) {
        requests.updateStatus(request.serviceRequestId(), RequestStatus.SEARCHING);
        outbox.enqueue(request.tenantId(), request.serviceRequestId(), SearchResumed.TYPE,
                new SearchResumed(request.serviceRequestId(), request.clientId(), trigger.value()), correlationId);
        offerNext(request.serviceRequestId(), correlationId);
    }
}
