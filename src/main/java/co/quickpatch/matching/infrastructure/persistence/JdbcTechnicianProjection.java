package co.quickpatch.matching.infrastructure.persistence;

import co.quickpatch.matching.application.events.Ports;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Proyección del técnico en {@code technician_availability} (DD 5.6). Un solo {@code INSERT ... ON CONFLICT}:
 * la fila nace en {@code no_disponible} (estado inicial) y, si ya existe, solo se copian la especialidad y la
 * verificación cuando el evento es más reciente que el último aplicado ({@code projection_updated_at}, ADR-017).
 * La disponibilidad ({@code status}, {@code updated_at}) no cambia: la fija el técnico o el matching (RN-M6), y
 * un servicio en curso no se interrumpe al suspender (RN-TP2).
 */
@Component
public class JdbcTechnicianProjection implements Ports.TechnicianProjection {

    private final JdbcTemplate jdbc;

    public JdbcTechnicianProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean apply(Ports.TechnicianSnapshot snapshot) {
        int rows = jdbc.update("""
                INSERT INTO technician_availability
                    (technician_id, tenant_id, specialty_id, verification_status, status, updated_at, projection_updated_at)
                VALUES (?, ?, ?, ?, 'no_disponible', now(), ?)
                ON CONFLICT (technician_id) DO UPDATE
                   SET specialty_id = EXCLUDED.specialty_id,
                       verification_status = EXCLUDED.verification_status,
                       projection_updated_at = EXCLUDED.projection_updated_at
                 WHERE technician_availability.projection_updated_at < EXCLUDED.projection_updated_at
                """, snapshot.technicianId(), snapshot.tenantId(), snapshot.specialtyId(),
                snapshot.verificationStatus(), snapshot.updatedAt());
        return rows == 1;
    }
}
