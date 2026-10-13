package co.quickpatch.matching.domain.technicians;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tipos del dominio de disponibilidad y cobertura del técnico (DD 5.6, 5.7 y 5.18). */
public final class Technicians {

    /** Estados de {@code technician_availability.status} (DD 5.6). */
    public static final String DISPONIBLE = "disponible";
    public static final String NO_DISPONIBLE = "no_disponible";
    public static final String OCUPADO = "ocupado";

    /** Estados que el técnico puede elegir; {@code ocupado} solo lo fija Matching (RN-M6). */
    public static final Set<String> SELECTABLE = Set.of(DISPONIBLE, NO_DISPONIBLE);

    /** Máximo de localidades por cobertura: las 20 de Bogotá (contrato matching 1.0.0). */
    public static final int MAX_LOCALITIES = 20;

    private Technicians() {
    }

    /** Disponibilidad actual del técnico (esquema {@code Availability}). */
    public record Availability(String status, OffsetDateTime updatedAt) {
    }

    /** Localidad de Bogotá sin su polígono (esquema {@code Locality}). */
    public record Locality(String code, String name) {
    }

    /** Cobertura del técnico (esquema {@code Coverage}). */
    public record Coverage(List<Locality> localities) {
    }

    /** Datos de entrada inválidos ({@code problems/validacion}), con los mensajes por campo. */
    public static class ValidationException extends RuntimeException {

        private final transient Map<String, List<String>> errors;

        public ValidationException(String field, String message) {
            super(message);
            this.errors = Map.of(field, List.of(message));
        }

        public Map<String, List<String>> errors() {
            return errors;
        }
    }

    /**
     * Matching todavía no tiene la fila del técnico porque no ha procesado su {@code technician.registered}
     * ({@code problems/no-encontrado}); el técnico puede reintentar segundos después.
     */
    public static class TechnicianNotFoundException extends RuntimeException {

        public TechnicianNotFoundException() {
            super("Matching todavía no tiene la disponibilidad del técnico.");
        }
    }

    /** El técnico está {@code ocupado} y no puede cambiar su disponibilidad (RN-M6, {@code problems/transicion-invalida}). */
    public static class InvalidTransitionException extends RuntimeException {

        public InvalidTransitionException(String message) {
            super(message);
        }
    }
}
