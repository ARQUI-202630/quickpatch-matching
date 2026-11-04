package co.quickpatch.matching.domain.events;

import java.util.Set;

/**
 * Estados de verificación del técnico (DD 5.3). Matching solo copia el estado que publica Identity; RN-TP1 y
 * RN-TP2 dejan participar en el matching únicamente a los {@link #APROBADO}.
 */
public final class VerificationStatus {

    public static final String PENDIENTE = "pendiente";
    public static final String APROBADO = "aprobado";
    public static final String RECHAZADO = "rechazado";
    public static final String SUSPENDIDO = "suspendido";

    private static final Set<String> ALL = Set.of(PENDIENTE, APROBADO, RECHAZADO, SUSPENDIDO);

    private VerificationStatus() {
    }

    /** Lanza {@link InvalidEventException} si el estado no es uno de los del contrato. */
    public static void require(String status) {
        if (status == null || !ALL.contains(status)) {
            throw new InvalidEventException("Estado de verificación desconocido: " + status);
        }
    }
}
