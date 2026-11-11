package co.quickpatch.matching.application.technicians;

/**
 * Proyección del técnico en {@code technician_availability} (DD 5.6). Crea la fila en {@code no_disponible}
 * si no existe; si existe, copia la especialidad y la verificación sin tocar la disponibilidad.
 */
public interface TechnicianProjection {
    /** Devuelve {@code false} si ya había una versión igual o más reciente (el evento llegó tarde). */
    boolean apply(TechnicianSnapshot snapshot);
}
