package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code data} de {@code technician.verification-changed} v1
 * ({@code contracts/kafka/events/technician.verification-changed.v1.json}). El administrador del tenant aprobó,
 * rechazó o suspendió al técnico; trae el estado completo de la proyección (ADR-017).
 */
public record TechnicianVerificationChanged(
        UUID technicianId,
        UUID specialtyId,
        String previousStatus,
        String verificationStatus,
        String reason,
        OffsetDateTime updatedAt) {

    public static final String TYPE = "technician.verification-changed";
    public static final int VERSION = 1;

    /** Valida los campos obligatorios, los estados y la longitud del motivo del contrato. */
    public void validate() {
        if (technicianId == null || specialtyId == null || updatedAt == null) {
            throw new InvalidEventException("Faltan campos obligatorios de technician.verification-changed.");
        }
        VerificationStatus.require(previousStatus);
        VerificationStatus.require(verificationStatus);
        if (reason != null && reason.length() > 500) {
            throw new InvalidEventException("El motivo supera 500 caracteres.");
        }
    }
}
