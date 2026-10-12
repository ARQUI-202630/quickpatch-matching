package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code data} de {@code technician.registered} v1 ({@code contracts/kafka/events/technician.registered.v1.json}).
 * Un técnico o proveedor se registró en Identity; Matching crea su fila de {@code technician_availability} (P-01).
 */
public record TechnicianRegistered(
        UUID technicianId,
        UUID specialtyId,
        UUID providerId,
        String verificationStatus,
        OffsetDateTime updatedAt) {

    public static final String TYPE = "technician.registered";
    public static final int VERSION = 1;

    /** Valida los campos obligatorios y el estado de verificación del contrato. */
    public void validate() {
        if (technicianId == null || specialtyId == null || updatedAt == null) {
            throw new InvalidEventException("Faltan campos obligatorios de technician.registered.");
        }
        VerificationStatus.require(verificationStatus);
    }
}
