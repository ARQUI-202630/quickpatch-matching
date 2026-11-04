package co.quickpatch.matching.domain.matching;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Modelo del algoritmo de matching (DD 5.8, 5.17 y 7.5). */
public final class Matching {

    private Matching() {
    }

    /** Estados de la búsqueda de una solicitud ({@code matching_requests.status}, DD 5.17). */
    public static final class RequestStatus {
        public static final String SEARCHING = "buscando";
        public static final String WAITING = "en_espera";
        public static final String ASSIGNED = "asignado";
        public static final String CANCELLED = "cancelado";

        private RequestStatus() {
        }
    }

    /** Copia mínima de la solicitud que Matching debe asignar ({@code matching_requests}). */
    public record MatchingRequest(
            UUID serviceRequestId,
            UUID tenantId,
            UUID clientId,
            UUID categoryId,
            double latitude,
            double longitude,
            String status,
            OffsetDateTime createdAt,
            String description) {
    }

    /** Técnico que cumple RN-M9 para una solicitud, con la distancia que lo ordena. */
    public record Candidate(UUID technicianId, BigDecimal distanceM) {
    }

    /** Oferta registrada en {@code matching_attempts} con respuesta {@code pendiente}. */
    public record Offer(UUID attemptId, UUID serviceRequestId, UUID technicianId, OffsetDateTime expiresAt) {
    }

    /** Respuestas de una oferta ({@code matching_attempts.response}, DD 5.8). */
    public static final class OfferResponse {
        public static final String PENDING = "pendiente";
        public static final String ACCEPTED = "aceptado";
        public static final String REJECTED = "rechazado";
        public static final String EXPIRED = "expirado";
        /** La solicitud se canceló con la oferta pendiente (RN-M5, SCRUM-476). */
        public static final String CANCELLED = "cancelado";

        private OfferResponse() {
        }
    }

    /**
     * Oferta que el técnico va a responder, bloqueada hasta el fin de la transacción. {@code expired} lo calcula la
     * base con su reloj ({@code expires_at <= now()}), el mismo con el que se fijó el vencimiento.
     */
    public record OfferToAnswer(UUID attemptId, UUID serviceRequestId, UUID technicianId, String response,
            boolean expired) {
    }

    /** Oferta pendiente como la ve el técnico (esquema {@code Offer} de {@code matching.v1.yaml}): sin dirección. */
    public record PendingOffer(UUID attemptId, UUID serviceRequestId, UUID categoryId, String description,
            String localityCode, String localityName, BigDecimal distanceM, OffsetDateTime offeredAt,
            OffsetDateTime expiresAt) {
    }

    /** La oferta no existe, es de otro técnico, ya se respondió o venció ({@code 409 oferta-no-disponible}). */
    public static class OfferNotAvailableException extends RuntimeException {

        public OfferNotAvailableException() {
            super("La oferta ya no está disponible.");
        }
    }

    /** El técnico dejó de estar {@code disponible} antes de aceptar ({@code 409 tecnico-no-disponible}, RN-M7). */
    public static class TechnicianNotAvailableException extends RuntimeException {

        public TechnicianNotAvailableException() {
            super("El técnico ya no está disponible para aceptar ofertas.");
        }
    }

    /**
     * Qué disparó el reintento de una solicitud en espera (RN-M10); es el {@code trigger} de
     * {@code matching.search-resumed}.
     */
    public enum ResumeTrigger {
        AVAILABILITY("disponibilidad"),
        COVERAGE("cobertura"),
        APPROVAL("aprobacion"),
        RELEASE("liberacion"),
        SWEEP("barrido");

        private final String value;

        ResumeTrigger(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Resultado de intentar ofrecer una solicitud al siguiente candidato. */
    public enum OfferOutcome {
        /** Se creó una oferta pendiente y se publicó {@code matching.offer-created}. */
        OFFERED,
        /** No quedan candidatos: la solicitud quedó {@code en_espera} (RN-M4, AC9-E4). */
        WAITING,
        /** No se hizo nada: la solicitud ya tiene una oferta pendiente (RN-M1) o no está buscando. */
        UNCHANGED
    }
}
