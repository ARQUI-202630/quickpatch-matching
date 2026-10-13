package co.quickpatch.matching.infrastructure.scheduling;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.matching.MatchingEngine;
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
 * Barrido de respaldo de RN-M10 (DD 7.5): cada {@code MATCHING_SWEEP_INTERVAL} busca los tenants con solicitudes en
 * espera con el rol {@code matching_scheduler} (P-06), que solo lee tenant y estado, y reanuda en cada tenant, con
 * {@code matching_app} y RLS, las que ya tienen un candidato. Cubre los casos que ningún disparador alcanzó, por
 * ejemplo una oferta rechazada por un técnico que después atiende otra solicitud.
 */
@Component
public class WaitingRequestsSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(WaitingRequestsSweeper.class);

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Ports.TenantTransaction tenantTransaction;
    private final MatchingEngine engine;
    private final String schedulerRole;
    private final int batchSize;

    public WaitingRequestsSweeper(TransactionTemplate transactions, JdbcTemplate jdbc,
            Ports.TenantTransaction tenantTransaction, MatchingEngine engine,
            @Value("${quickpatch.matching.scheduler-role:}") String schedulerRole,
            @Value("${quickpatch.matching.sweep-batch-size:20}") int batchSize) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.tenantTransaction = tenantTransaction;
        this.engine = engine;
        this.schedulerRole = DatabaseRoles.validate(schedulerRole);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${quickpatch.matching.sweep-interval:PT1M}",
            initialDelayString = "${quickpatch.matching.sweep-interval:PT1M}")
    public void run() {
        List<UUID> tenants;
        try {
            tenants = transactions.execute(status -> {
                DatabaseRoles.adopt(jdbc, schedulerRole);
                return jdbc.queryForList(
                        "SELECT DISTINCT tenant_id FROM matching_requests WHERE status = 'en_espera'", UUID.class);
            });
        } catch (RuntimeException e) {
            LOG.error("Falló el barrido de solicitudes en espera.", e);
            return;
        }
        if (tenants == null) {
            return;
        }
        for (var tenant : tenants) {
            MDC.put("tenantId", tenant.toString());
            try {
                var resumed = tenantTransaction.execute(tenant, () -> engine.sweepWaiting(batchSize));
                if (resumed > 0) {
                    LOG.info("Barrido RN-M10: {} solicitudes en espera reanudadas.", resumed);
                }
            } catch (RuntimeException e) {
                LOG.error("Falló el barrido de solicitudes en espera del tenant.", e);
            } finally {
                MDC.remove("tenantId");
            }
        }
    }
}
