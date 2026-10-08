package co.quickpatch.matching.domain.events;

/** El mensaje no cumple el contrato del evento; se descarta sin reintentar, porque reintentar no lo corrige. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
