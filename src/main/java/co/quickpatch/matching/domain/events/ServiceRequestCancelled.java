package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * {@code data} de {@code service-request.cancelled} v1 ({@code contracts/kafka/events/service-request.cancelled.v1.json},
 * RF-36, SCRUM-474). {@code reason} es un dato del usuario: nunca se escribe en los logs.
 */
public record ServiceRequestCancelled(
        UUID serviceRequestId,
        UUID clientId,
        UUID technicianId,
        String cancelledByType,
        String reason,
        OffsetDateTime cancelledAt) {

    public static final String TYPE = "service-request.cancelled";
    public static final int VERSION = 1;

    private static final Set<String> CANCELLED_BY = Set.of("cliente", "admin_tenant", "sistema");

    /** Valida los campos obligatorios del contrato; {@code technicianId} es opcional. */
    public void validate() {
        if (serviceRequestId == null || clientId == null || cancelledAt == null || reason == null || reason.isEmpty()) {
            throw new InvalidEventException("Faltan campos obligatorios de service-request.cancelled.");
        }
        if (!CANCELLED_BY.contains(cancelledByType)) {
            throw new InvalidEventException("cancelledByType no es cliente, admin_tenant ni sistema.");
        }
        if (reason.length() > 500) {
            throw new InvalidEventException("El motivo supera los 500 caracteres.");
        }
    }
}
