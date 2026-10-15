package co.quickpatch.matching.application.matching;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.MatchingEvents.TechnicianAssigned;
import co.quickpatch.matching.domain.matching.Matching.OfferNotAvailableException;
import co.quickpatch.matching.domain.matching.Matching.OfferResponse;
import co.quickpatch.matching.domain.matching.Matching.OfferToAnswer;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
import co.quickpatch.matching.domain.matching.Matching.RequestStatus;
import co.quickpatch.matching.domain.matching.Matching.TechnicianNotAvailableException;
import co.quickpatch.matching.domain.technicians.Technicians;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ofertas del técnico (RF-10, SCRUM-30 y SCRUM-79; {@code matching.v1.yaml} 1.0.0): las lista, las acepta o las
 * rechaza, y vence las que nadie respondió (RN-M8). El técnico y el tenant salen del token; cada operación corre en
 * la transacción de ese tenant, así que RLS solo deja ver sus filas.
 *
 * <p>Orden de bloqueo: oferta → técnico → solicitud, el mismo que usan los cambios de disponibilidad (técnico →
 * solicitud), para que dos transacciones no se bloqueen entre sí.
 */
public class OfferService {

    /** Único filtro de estado de esta versión del contrato. */
    public static final String PENDING_FILTER = OfferResponse.PENDING;

    static final int MAX_PAGE_SIZE = 100;

    private final Ports.TenantTransaction transaction;
    private final MatchingPorts.OfferStore offers;
    private final MatchingPorts.RequestStore requests;
    private final TechnicianPorts.AvailabilityStore availability;
    private final MatchingPorts.Outbox outbox;
    private final MatchingPorts.CorrelationContext correlation;
    private final MatchingEngine engine;

    public OfferService(Ports.TenantTransaction transaction, MatchingPorts.OfferStore offers,
            MatchingPorts.RequestStore requests, TechnicianPorts.AvailabilityStore availability,
            MatchingPorts.Outbox outbox, MatchingPorts.CorrelationContext correlation, MatchingEngine engine) {
        this.transaction = Objects.requireNonNull(transaction);
        this.offers = Objects.requireNonNull(offers);
        this.requests = Objects.requireNonNull(requests);
        this.availability = Objects.requireNonNull(availability);
        this.outbox = Objects.requireNonNull(outbox);
        this.correlation = Objects.requireNonNull(correlation);
        this.engine = Objects.requireNonNull(engine);
    }

    /** Página de ofertas pendientes. */
    public record Page(List<PendingOffer> items, int page, int size, int totalItems) {
    }

    /**
     * Ofertas pendientes y vigentes del técnico (RN-M2). Como tiene a lo sumo una pendiente a la vez (RN-M7), la
     * página tiene cero o un elemento. Nunca incluye la dirección (RN-SR13).
     */
    public Page listPending(UUID tenantId, UUID technicianId, String status, int page, int size) {
        if (status != null && !PENDING_FILTER.equals(status)) {
            throw new ValidationException("status", "En esta versión solo se consultan las ofertas pendientes.");
        }
        if (page < 1) {
            throw new ValidationException("page", "La página empieza en 1.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size", "El tamaño de página va de 1 a " + MAX_PAGE_SIZE + ".");
        }
        return transaction.execute(tenantId, () -> new Page(
                offers.pendingFor(technicianId, (page - 1) * size, size), page, size,
                offers.countPendingFor(technicianId)));
    }

    /**
     * Acepta la oferta (RN-M2, RN-M4): el técnico pasa a {@code ocupado} (RN-M6), la solicitud a {@code asignado} y
     * se publica {@code matching.technician-assigned}, todo en la misma transacción (ADR-007).
     *
     * @throws OfferNotAvailableException si la oferta no existe, es de otro técnico, ya se respondió o venció
     * @throws TechnicianNotAvailableException si el técnico dejó de estar {@code disponible} (RN-M7)
     */
    public void accept(UUID tenantId, UUID technicianId, UUID attemptId) {
        transaction.execute(tenantId, () -> {
            var offer = answerable(technicianId, attemptId);
            var current = availability.findForUpdate(technicianId)
                    .filter(a -> Technicians.DISPONIBLE.equals(a.status()))
                    .orElseThrow(TechnicianNotAvailableException::new);
            var request = requests.findForUpdate(offer.serviceRequestId())
                    .filter(r -> RequestStatus.SEARCHING.equals(r.status()))
                    .orElseThrow(OfferNotAvailableException::new);

            var assignedAt = offers.respond(attemptId, OfferResponse.ACCEPTED);
            availability.update(technicianId, Technicians.OCUPADO);
            requests.updateStatus(request.serviceRequestId(), RequestStatus.ASSIGNED);
            outbox.enqueue(tenantId, request.serviceRequestId(), TechnicianAssigned.TYPE,
                    new TechnicianAssigned(request.serviceRequestId(), request.clientId(), attemptId, technicianId,
                            assignedAt),
                    correlation.current());
            return current;
        });
    }

    /**
     * Rechaza la oferta sin penalización (RF-10) y ofrece la solicitud al siguiente candidato (RN-M3); si no queda
     * ninguno, la solicitud pasa a {@code en_espera} con {@code matching.no-technician-available}.
     *
     * @throws OfferNotAvailableException si la oferta no existe, es de otro técnico, ya se respondió o venció
     */
    public void reject(UUID tenantId, UUID technicianId, UUID attemptId) {
        transaction.execute(tenantId, () -> {
            var offer = answerable(technicianId, attemptId);
            offers.respond(attemptId, OfferResponse.REJECTED);
            return engine.offerNext(offer.serviceRequestId(), correlation.current());
        });
    }

    /**
     * Vence las ofertas pendientes que nadie respondió a tiempo en el tenant de la transacción (RN-M8) y ofrece cada
     * solicitud al siguiente candidato (RN-M3). Lo llama el barrido periódico. Devuelve cuántas venció.
     */
    public int expireOverdue(int limit) {
        var expired = offers.expiredForUpdate(limit);
        for (var offer : expired) {
            offers.respond(offer.attemptId(), OfferResponse.EXPIRED);
            engine.offerNext(offer.serviceRequestId(), UUID.randomUUID());
        }
        return expired.size();
    }

    /**
     * La oferta pendiente y vigente del técnico, bloqueada. Una oferta inexistente y una ajena dan el mismo error,
     * para no revelar las ofertas de otros técnicos (SCRUM-30, criterio 5).
     */
    private OfferToAnswer answerable(UUID technicianId, UUID attemptId) {
        return offers.findOfferForUpdate(attemptId)
                .filter(o -> o.technicianId().equals(technicianId))
                .filter(o -> OfferResponse.PENDING.equals(o.response()) && !o.expired())
                .orElseThrow(OfferNotAvailableException::new);
    }
}
