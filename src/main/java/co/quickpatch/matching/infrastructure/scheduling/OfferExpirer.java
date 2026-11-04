package co.quickpatch.matching.infrastructure.scheduling;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.matching.OfferService;
import co.quickpatch.matching.infrastructure.persistence.DatabaseRoles;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vencimiento de ofertas (RN-M3, RN-M8; SCRUM-79): cada {@code MATCHING_EXPIRY_INTERVAL} busca, con el rol
 * {@code matching_scheduler} (P-06), los tenants con ofertas pendientes ya vencidas, y en cada tenant, con
 * {@code matching_app} y RLS, las marca {@code expirado} y ofrece la solicitud al siguiente candidato. Una oferta
 * vencida que todavía no se barrió ya no se puede aceptar: el servicio compara {@code expires_at} con la hora de la
 * base al responder.
 */
@Component
public class OfferExpirer {

    private static final Logger LOG = LoggerFactory.getLogger(OfferExpirer.class);

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Ports.TenantTransaction tenantTransaction;
    private final OfferService offers;
    private final String schedulerRole;
    private final int batchSize;

    public OfferExpirer(TransactionTemplate transactions, JdbcTemplate jdbc, Ports.TenantTransaction tenantTransaction,
            OfferService offers, @Value("${quickpatch.matching.scheduler-role:}") String schedulerRole,
            @Value("${quickpatch.matching.expiry-batch-size:50}") int batchSize) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.tenantTransaction = tenantTransaction;
        this.offers = offers;
        this.schedulerRole = DatabaseRoles.validate(schedulerRole);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${quickpatch.matching.expiry-interval:PT15S}",
            initialDelayString = "${quickpatch.matching.expiry-interval:PT15S}")
    public void run() {
        List<UUID> tenants;
        try {
            tenants = transactions.execute(status -> {
                DatabaseRoles.adopt(jdbc, schedulerRole);
                return jdbc.queryForList("""
                        SELECT DISTINCT tenant_id FROM matching_attempts
                         WHERE response = 'pendiente' AND expires_at <= now()
                        """, UUID.class);
            });
        } catch (RuntimeException e) {
            LOG.error("Falló la búsqueda de ofertas vencidas.", e);
            return;
        }
        if (tenants == null) {
            return;
        }
        for (var tenant : tenants) {
            MDC.put("tenantId", tenant.toString());
            try {
                var expired = tenantTransaction.execute(tenant, () -> offers.expireOverdue(batchSize));
                if (expired > 0) {
                    LOG.info("RN-M8: {} ofertas vencidas; las solicitudes pasan al siguiente candidato.", expired);
                }
            } catch (RuntimeException e) {
                LOG.error("Falló el vencimiento de ofertas del tenant.", e);
            } finally {
                MDC.remove("tenantId");
            }
        }
    }
}
