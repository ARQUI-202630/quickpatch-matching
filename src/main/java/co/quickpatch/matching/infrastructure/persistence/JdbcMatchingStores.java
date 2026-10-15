package co.quickpatch.matching.infrastructure.persistence;

import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.domain.matching.Matching.Candidate;
import co.quickpatch.matching.domain.matching.Matching.MatchingRequest;
import co.quickpatch.matching.domain.matching.Matching.Offer;
import co.quickpatch.matching.domain.matching.Matching.OfferToAnswer;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Solicitudes, candidatos y ofertas con JDBC y PostGIS (DD 5.8, 5.17 y 7.5). Todo corre dentro de la transacción
 * por tenant, así que RLS solo deja ver las filas de ese tenant; las condiciones de tenant en los JOIN son una
 * segunda barrera.
 *
 * <p><b>Cercanía.</b> Matching no guarda la posición del técnico, solo su cobertura. La distancia de un candidato
 * es la que hay, en metros sobre el elipsoide, entre la ubicación de la solicitud y el centroide de toda su
 * cobertura: quien cubre pocas localidades alrededor de la solicitud queda antes que quien cubre media ciudad. A
 * igual distancia va primero quien lleva más tiempo disponible.
 */
@Component
public class JdbcMatchingStores
        implements MatchingPorts.RequestStore, MatchingPorts.CandidateSearch, MatchingPorts.AttemptStore,
        MatchingPorts.OfferStore {

    private static final String REQUEST_COLUMNS = """
            r.service_request_id, r.tenant_id, r.client_id, r.category_id,
            ST_Y(r.location) AS latitude, ST_X(r.location) AS longitude, r.status, r.created_at, r.description
            """;

    /**
     * RN-M9 para el técnico {@code t} y la solicitud {@code r}, salvo la cobertura: aprobado, disponible, de la
     * especialidad, sin otra oferta pendiente y sin una oferta anterior para esta solicitud.
     */
    private static final String ELIGIBLE = """
            t.tenant_id = r.tenant_id
            AND t.specialty_id = r.category_id
            AND t.verification_status = 'aprobado'
            AND t.status = 'disponible'
            AND NOT EXISTS (SELECT 1 FROM matching_attempts a
                             WHERE a.technician_id = t.technician_id
                               AND (a.response = 'pendiente' OR a.service_request_id = r.service_request_id))
            """;

    private final JdbcTemplate jdbc;

    public JdbcMatchingStores(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(MatchingRequest request) {
        return jdbc.update("""
                INSERT INTO matching_requests
                    (service_request_id, tenant_id, client_id, category_id, location, status, created_at, updated_at,
                     description)
                VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, now(), ?)
                ON CONFLICT (service_request_id) DO NOTHING
                """, request.serviceRequestId(), request.tenantId(), request.clientId(), request.categoryId(),
                request.longitude(), request.latitude(), request.status(), request.createdAt(),
                request.description()) == 1;
    }

    @Override
    public Optional<MatchingRequest> findForUpdate(UUID serviceRequestId) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM matching_requests r WHERE r.service_request_id = ? FOR UPDATE",
                JdbcMatchingStores::request, serviceRequestId).stream().findFirst();
    }

    @Override
    public void updateStatus(UUID serviceRequestId, String status) {
        jdbc.update("UPDATE matching_requests SET status = ?, updated_at = now() WHERE service_request_id = ?",
                status, serviceRequestId);
    }

    @Override
    public Optional<MatchingRequest> oldestWaitingFor(UUID technicianId) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + """
                  FROM matching_requests r
                 WHERE r.status = 'en_espera'
                   AND EXISTS (SELECT 1 FROM technician_availability t
                                WHERE t.technician_id = ?
                                  AND\s""" + ELIGIBLE + """
                                  AND EXISTS (SELECT 1 FROM coverage_zones z
                                               WHERE z.technician_id = t.technician_id
                                                 AND ST_Covers(z.area, r.location)))
                 ORDER BY r.created_at, r.service_request_id
                 LIMIT 1
                 FOR UPDATE OF r SKIP LOCKED
                """, JdbcMatchingStores::request, technicianId).stream().findFirst();
    }

    @Override
    public List<MatchingRequest> waiting(int limit) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + """
                  FROM matching_requests r
                 WHERE r.status = 'en_espera'
                 ORDER BY r.created_at, r.service_request_id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, JdbcMatchingStores::request, limit);
    }

    @Override
    public List<Candidate> find(UUID serviceRequestId, int limit) {
        return jdbc.query("""
                SELECT t.technician_id,
                       round(ST_Distance(ST_Centroid(ST_Collect(z.area))::geography, r.location::geography)::numeric, 1)
                           AS distance_m
                  FROM matching_requests r
                  JOIN technician_availability t ON\s""" + ELIGIBLE + """
                  JOIN coverage_zones z ON z.technician_id = t.technician_id AND z.tenant_id = t.tenant_id
                 WHERE r.service_request_id = ?
                 GROUP BY t.technician_id, t.updated_at, r.location
                HAVING bool_or(ST_Covers(z.area, r.location))
                 ORDER BY distance_m, t.updated_at, t.technician_id
                 LIMIT ?
                """, (rs, row) -> new Candidate(rs.getObject("technician_id", UUID.class),
                rs.getBigDecimal("distance_m")), serviceRequestId, limit);
    }

    @Override
    public boolean hasPendingOffer(UUID serviceRequestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM matching_attempts
                                WHERE service_request_id = ? AND response = 'pendiente')
                """, Boolean.class, serviceRequestId));
    }

    @Override
    public Optional<Offer> offer(MatchingRequest request, Candidate candidate, Duration ttl) {
        // Sin destino en ON CONFLICT: cubre los dos índices únicos parciales (RN-M1 y RN-M7) sin abortar la transacción.
        return jdbc.query("""
                INSERT INTO matching_attempts
                    (id, service_request_id, technician_id, tenant_id, offered_at, response, expires_at, distance_m)
                VALUES (?, ?, ?, ?, now(), 'pendiente', now() + make_interval(secs => ?), ?)
                ON CONFLICT DO NOTHING
                RETURNING id, service_request_id, technician_id, expires_at
                """, (rs, row) -> new Offer(rs.getObject("id", UUID.class),
                        rs.getObject("service_request_id", UUID.class), rs.getObject("technician_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                UUID.randomUUID(), request.serviceRequestId(), candidate.technicianId(), request.tenantId(),
                ttl.toMillis() / 1000.0, candidate.distanceM()).stream().findFirst();
    }

    @Override
    public int count(UUID serviceRequestId) {
        var total = jdbc.queryForObject("SELECT count(*) FROM matching_attempts WHERE service_request_id = ?",
                Integer.class, serviceRequestId);
        return total == null ? 0 : total;
    }

    @Override
    public List<PendingOffer> pendingFor(UUID technicianId, int offset, int limit) {
        // La localidad sale del punto de la solicitud; en el borde entre dos localidades se toma la de menor código.
        return jdbc.query("""
                SELECT a.id, a.service_request_id, r.category_id, r.description, l.code, l.name, a.distance_m,
                       a.offered_at, a.expires_at
                  FROM matching_attempts a
                  JOIN matching_requests r ON r.service_request_id = a.service_request_id AND r.tenant_id = a.tenant_id
                  LEFT JOIN LATERAL (SELECT code, name FROM localities
                                      WHERE ST_Covers(area, r.location) ORDER BY code LIMIT 1) l ON true
                 WHERE a.technician_id = ? AND a.response = 'pendiente' AND a.expires_at > now()
                 ORDER BY a.offered_at DESC, a.id
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new PendingOffer(rs.getObject("id", UUID.class),
                        rs.getObject("service_request_id", UUID.class), rs.getObject("category_id", UUID.class),
                        rs.getString("description"), rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("distance_m"), rs.getObject("offered_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                technicianId, limit, offset);
    }

    @Override
    public int countPendingFor(UUID technicianId) {
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM matching_attempts
                 WHERE technician_id = ? AND response = 'pendiente' AND expires_at > now()
                """, Integer.class, technicianId);
        return total == null ? 0 : total;
    }

    @Override
    public Optional<OfferToAnswer> findOfferForUpdate(UUID attemptId) {
        return jdbc.query("""
                SELECT id, service_request_id, technician_id, response, expires_at <= now() AS expired
                  FROM matching_attempts WHERE id = ? FOR UPDATE
                """, JdbcMatchingStores::offerToAnswer, attemptId).stream().findFirst();
    }

    @Override
    public OffsetDateTime respond(UUID attemptId, String response) {
        return jdbc.queryForObject(
                "UPDATE matching_attempts SET response = ?, responded_at = now() WHERE id = ? RETURNING responded_at",
                OffsetDateTime.class, response, attemptId);
    }

    @Override
    public List<OfferToAnswer> expiredForUpdate(int limit) {
        return jdbc.query("""
                SELECT id, service_request_id, technician_id, response, true AS expired
                  FROM matching_attempts
                 WHERE response = 'pendiente' AND expires_at <= now()
                 ORDER BY expires_at, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, JdbcMatchingStores::offerToAnswer, limit);
    }

    private static OfferToAnswer offerToAnswer(ResultSet rs, int row) throws SQLException {
        return new OfferToAnswer(rs.getObject("id", UUID.class), rs.getObject("service_request_id", UUID.class),
                rs.getObject("technician_id", UUID.class), rs.getString("response"), rs.getBoolean("expired"));
    }

    private static MatchingRequest request(ResultSet rs, int row) throws SQLException {
        return new MatchingRequest(rs.getObject("service_request_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("client_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("description"));
    }
}
