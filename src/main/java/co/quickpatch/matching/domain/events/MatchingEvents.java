package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code data} de los eventos que publica Matching ({@code contracts/kafka/events/matching.*.v1.json}). La clave
 * del mensaje es siempre {@code serviceRequestId}. Ninguno lleva la dirección ni datos del cliente (RN-SR13).
 */
public final class MatchingEvents {

    /** Valor de {@code producer} en el sobre. */
    public static final String PRODUCER = "matching-service";

    /** Versión de los cuatro contratos. */
    public static final int VERSION = 1;

    private MatchingEvents() {
    }

    /** {@code matching.offer-created}: oferta a un técnico elegible que vence en {@code expiresAt} (RN-M8). */
    public record OfferCreated(UUID serviceRequestId, UUID attemptId, UUID technicianId, OffsetDateTime expiresAt) {
        public static final String TYPE = "matching.offer-created";
    }

    /**
     * {@code matching.technician-assigned}: el técnico aceptó la oferta (RN-M4). Lleva {@code clientId} para que
     * Communication avise al cliente.
     */
    public record TechnicianAssigned(UUID serviceRequestId, UUID clientId, UUID attemptId, UUID technicianId,
            OffsetDateTime assignedAt) {
        public static final String TYPE = "matching.technician-assigned";
    }

    /** {@code matching.no-technician-available}: se agotaron los candidatos (RN-M4, AC9-E4). */
    public record NoTechnicianAvailable(UUID serviceRequestId, UUID clientId, int attemptsCount) {
        public static final String TYPE = "matching.no-technician-available";
    }

    /** {@code matching.search-resumed}: una solicitud en espera vuelve a buscar técnico (RN-M10). */
    public record SearchResumed(UUID serviceRequestId, UUID clientId, String trigger) {
        public static final String TYPE = "matching.search-resumed";
    }
}
