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
            OffsetDateTime createdAt) {
    }

    /** Técnico que cumple RN-M9 para una solicitud, con la distancia que lo ordena. */
    public record Candidate(UUID technicianId, BigDecimal distanceM) {
    }

    /** Oferta registrada en {@code matching_attempts} con respuesta {@code pendiente}. */
    public record Offer(UUID attemptId, UUID serviceRequestId, UUID technicianId, OffsetDateTime expiresAt) {
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
