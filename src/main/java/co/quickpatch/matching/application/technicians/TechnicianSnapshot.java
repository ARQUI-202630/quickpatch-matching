package co.quickpatch.matching.application.technicians;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Estado del técnico que publica Identity (P-01): especialidad y verificación, con su versión (ADR-017). */
public record TechnicianSnapshot(UUID tenantId, UUID technicianId, UUID specialtyId, String verificationStatus,
        OffsetDateTime updatedAt) {
}
