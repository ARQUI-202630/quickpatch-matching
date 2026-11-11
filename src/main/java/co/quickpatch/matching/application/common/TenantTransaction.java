package co.quickpatch.matching.application.common;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ejecuta un trabajo en una transacción que primero fija el tenant de la sesión
 * ({@code set_config('app.current_tenant', ..., true)}), para que RLS aplique (ADR-005, DD 10.2).
 */
public interface TenantTransaction {
    <T> T execute(UUID tenantId, Supplier<T> work);
}
