package co.quickpatch.matching.infrastructure.persistence;

import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.Locality;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Disponibilidad, cobertura y localidades con JDBC (DD 5.6, 5.7 y 5.18). Las consultas sobre
 * {@code technician_availability} y {@code coverage_zones} corren dentro de la transacción por tenant, así que RLS
 * solo deja ver las filas del tenant del token.
 */
@Component
public class JdbcTechnicianStores
        implements TechnicianPorts.AvailabilityStore, TechnicianPorts.CoverageStore, TechnicianPorts.LocalityCatalog {

    private final JdbcTemplate jdbc;

    public JdbcTechnicianStores(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Availability> find(UUID technicianId) {
        return jdbc.query("SELECT status, updated_at FROM technician_availability WHERE technician_id = ?",
                JdbcTechnicianStores::availability, technicianId).stream().findFirst();
    }

    @Override
    public Optional<Availability> findForUpdate(UUID technicianId) {
        return jdbc.query("SELECT status, updated_at FROM technician_availability WHERE technician_id = ? FOR UPDATE",
                JdbcTechnicianStores::availability, technicianId).stream().findFirst();
    }

    @Override
    public Availability update(UUID technicianId, String status) {
        return jdbc.queryForObject("""
                UPDATE technician_availability SET status = ?, updated_at = now()
                 WHERE technician_id = ?
                RETURNING status, updated_at
                """, JdbcTechnicianStores::availability, status, technicianId);
    }

    @Override
    public List<Locality> list(UUID technicianId) {
        return jdbc.query("""
                SELECT l.code, l.name
                  FROM coverage_zones z JOIN localities l ON l.code = z.locality_code
                 WHERE z.technician_id = ?
                 ORDER BY l.code
                """, JdbcTechnicianStores::locality, technicianId);
    }

    @Override
    public void replace(UUID tenantId, UUID technicianId, List<String> localityCodes) {
        jdbc.update("DELETE FROM coverage_zones WHERE technician_id = ?", technicianId);
        // El polígono se copia de localities para que la búsqueda de candidatos no necesite un JOIN (DD 5.7).
        jdbc.batchUpdate("""
                INSERT INTO coverage_zones (id, technician_id, tenant_id, locality_code, area, created_at)
                SELECT ?, ?, ?, code, area, now() FROM localities WHERE code = ?
                """, localityCodes.stream()
                .map(code -> new Object[] {UUID.randomUUID(), technicianId, tenantId, code})
                .toList());
    }

    @Override
    public List<Locality> all() {
        return jdbc.query("SELECT code, name FROM localities ORDER BY code", JdbcTechnicianStores::locality);
    }

    private static Availability availability(ResultSet rs, int row) throws SQLException {
        return new Availability(rs.getString("status"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Locality locality(ResultSet rs, int row) throws SQLException {
        return new Locality(rs.getString("code"), rs.getString("name"));
    }
}
