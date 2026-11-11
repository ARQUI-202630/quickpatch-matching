package co.quickpatch.matching.application.matching;

import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;

/**
 * Inicia el matching de una solicitud nueva (RF-09, RN-M1 a RN-M7). Se ejecuta dentro de la misma
 * transacción en que se registra el evento, así que el efecto y el registro se confirman juntos.
 */
public interface MatchingStarter {
    void start(EventEnvelope<ServiceRequestCreated> event);
}
