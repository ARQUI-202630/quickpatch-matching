package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code data} de {@code service-request.completed} v1 ({@code contracts/kafka/events/service-request.completed.v1.json},
 * RF-15, SCRUM-94). Matching solo usa la solicitud: el técnico que libera sale de su propio registro de la oferta aceptada.
 */
public record ServiceRequestCompleted(
        UUID serviceRequestId,
        UUID clientId,
        UUID companyId,
        UUID technicianId,
        UUID quoteId,
        String totalAmount,
        OffsetDateTime completedAt) {

    public static final String TYPE = "service-request.completed";
    public static final int VERSION = 1;

    /** Valida los campos obligatorios del contrato; {@code companyId} es opcional. */
    public void validate() {
        if (serviceRequestId == null || clientId == null || technicianId == null || quoteId == null
                || totalAmount == null || completedAt == null) {
            throw new InvalidEventException("Faltan campos obligatorios de service-request.completed.");
        }
    }
}
