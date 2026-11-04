package co.quickpatch.matching.infrastructure.persistence;

import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Roles que el servicio adopta con {@code SET LOCAL ROLE} dentro de una transacción (DD 10.2): el publicador del
 * Outbox ({@code matching_outbox}) y las tareas programadas ({@code matching_scheduler}). Los dos tienen BYPASSRLS
 * y {@code matching_app} los recibe con {@code INHERIT FALSE}, así que fuera de esa transacción no tiene sus
 * permisos. Vacío solo en desarrollo local, donde el usuario de la base ya ve todas las filas.
 */
public final class DatabaseRoles {

    private static final Pattern NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    private DatabaseRoles() {
    }

    /** Devuelve el nombre sin espacios; lanza si no es un nombre de rol válido, porque no admite parámetros SQL. */
    public static String validate(String role) {
        var trimmed = role == null ? "" : role.trim();
        if (!trimmed.isEmpty() && !NAME.matcher(trimmed).matches()) {
            throw new IllegalStateException("Nombre de rol de base de datos inválido: " + role);
        }
        return trimmed;
    }

    /** Adopta el rol hasta el fin de la transacción en curso; no hace nada si está vacío. */
    public static void adopt(JdbcTemplate jdbc, String role) {
        if (!role.isEmpty()) {
            jdbc.execute("SET LOCAL ROLE \"" + validate(role) + "\"");
        }
    }
}
