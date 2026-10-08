package co.quickpatch.matching.infrastructure.persistence;

import co.quickpatch.matching.application.events.Ports;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transacción por tenant (DD 10.2): fija {@code app.current_tenant} con {@code set_config(..., true)}, que dura
 * solo lo que dura la transacción, y después ejecuta el trabajo. Sin tenant fijado, RLS no deja ver ni escribir.
 */
@Component
public class JdbcTenantTransaction implements Ports.TenantTransaction {

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    public JdbcTenantTransaction(TransactionTemplate transactions, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @Override
    public <T> T execute(UUID tenantId, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.current_tenant', ?, true)", String.class, tenantId.toString());
            return work.get();
        });
    }
}
