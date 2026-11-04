package co.quickpatch.matching.application.matching;

import co.quickpatch.matching.domain.matching.Matching.Candidate;
import co.quickpatch.matching.domain.matching.Matching.MatchingRequest;
import co.quickpatch.matching.domain.matching.Matching.Offer;
import co.quickpatch.matching.domain.matching.Matching.OfferToAnswer;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Puertos del algoritmo de matching; los implementa la infraestructura. Todos corren dentro de la transacción por
 * tenant ({@code Ports.TenantTransaction}), así que RLS solo deja ver las filas de ese tenant.
 */
public final class MatchingPorts {

    private MatchingPorts() {
    }

    /** Solicitudes en {@code matching_requests} (DD 5.17). */
    public interface RequestStore {
        /** Crea la solicitud en {@code buscando}; devuelve {@code false} si ya existía. */
        boolean create(MatchingRequest request);

        /** Busca y bloquea la solicitud ({@code FOR UPDATE}) hasta el fin de la transacción. */
        Optional<MatchingRequest> findForUpdate(UUID serviceRequestId);

        /** Cambia el estado y {@code updated_at}. */
        void updateStatus(UUID serviceRequestId, String status);

        /**
         * La solicitud en espera más antigua para la que el técnico es candidato (RN-M9, RN-M10), bloqueada. Salta
         * las que otra transacción tiene bloqueadas.
         */
        Optional<MatchingRequest> oldestWaitingFor(UUID technicianId);

        /** Las solicitudes en espera, de la más antigua a la más reciente, hasta {@code limit}, bloqueadas. */
        List<MatchingRequest> waiting(int limit);
    }

    /** Búsqueda de candidatos con PostGIS (RN-M9). */
    public interface CandidateSearch {
        /** Los técnicos que cumplen RN-M9 para la solicitud, ordenados por cercanía, hasta {@code limit}. */
        List<Candidate> find(UUID serviceRequestId, int limit);
    }

    /** Ofertas en {@code matching_attempts} (DD 5.8). */
    public interface AttemptStore {
        /** {@code true} si la solicitud ya tiene una oferta {@code pendiente} (RN-M1). */
        boolean hasPendingOffer(UUID serviceRequestId);

        /**
         * Registra una oferta pendiente que vence en {@code ttl} (RN-M8). Devuelve vacío si un índice único lo impide
         * porque otra transacción ofreció al mismo técnico o la misma solicitud en paralelo (RN-M1, RN-M7).
         */
        Optional<Offer> offer(MatchingRequest request, Candidate candidate, Duration ttl);

        /** Ofertas hechas para la solicitud, en cualquier estado. */
        int count(UUID serviceRequestId);
    }

    /** Respuesta y vencimiento de las ofertas (RF-10, RN-M2, RN-M3, RN-M8; SCRUM-79). */
    public interface OfferStore {
        /** Ofertas pendientes y vigentes del técnico, de la más reciente a la más antigua (RN-M2). */
        List<PendingOffer> pendingFor(UUID technicianId, int offset, int limit);

        /** Total de ofertas pendientes y vigentes del técnico. */
        int countPendingFor(UUID technicianId);

        /** Busca y bloquea la oferta ({@code FOR UPDATE}) hasta el fin de la transacción. */
        Optional<OfferToAnswer> findOfferForUpdate(UUID attemptId);

        /** Fija la respuesta y {@code responded_at = now()}; devuelve el momento de la respuesta. */
        OffsetDateTime respond(UUID attemptId, String response);

        /**
         * Ofertas pendientes ya vencidas, bloqueadas, hasta {@code limit} (RN-M8). Salta las que otra transacción
         * tiene bloqueadas, por ejemplo un técnico que está aceptando en ese momento.
         */
        List<OfferToAnswer> expiredForUpdate(int limit);
    }

    /** Lo que necesita la cancelación de una solicitud (RN-M5, RN-M6; SCRUM-476). */
    public interface CancellationStore {
        /** La oferta pendiente de la solicitud, bloqueada ({@code FOR UPDATE}); vacío si no hay (RN-M1: a lo sumo una). */
        Optional<OfferToAnswer> pendingOfferForUpdate(UUID serviceRequestId);

        /** El técnico que aceptó la oferta de la solicitud; vacío si nadie la aceptó. */
        Optional<UUID> acceptedTechnician(UUID serviceRequestId);
    }

    /** Transactional Outbox (ADR-007, DD 5.15): el evento se guarda en la transacción del cambio. */
    public interface Outbox {
        /**
         * Encola el evento con el sobre del contrato; {@code aggregateId} es la clave del mensaje en Kafka.
         *
         * @param data el contenido de {@code data}, que se serializa a JSON
         */
        void enqueue(UUID tenantId, UUID aggregateId, String eventType, Object data, UUID correlationId);
    }

    /** Correlación de la operación en curso (petición HTTP o evento consumido); una nueva si no hay ninguna. */
    public interface CorrelationContext {
        UUID current();
    }
}
