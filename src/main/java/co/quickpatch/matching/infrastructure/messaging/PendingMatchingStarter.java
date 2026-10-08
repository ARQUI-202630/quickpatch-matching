package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación provisional: deja constancia de que la solicitud llegó a Matching. La búsqueda de candidatos y
 * las ofertas ({@code matching_attempts}, RN-M1 a RN-M7) se implementan en la historia de matching y reemplazan
 * este adaptador sin cambiar el consumidor.
 */
@Component
public class PendingMatchingStarter implements Ports.MatchingStarter {

    private static final Logger LOG = LoggerFactory.getLogger(PendingMatchingStarter.class);

    @Override
    public void start(EventEnvelope<ServiceRequestCreated> event) {
        LOG.info("Solicitud {} recibida para matching (categoría {}).",
                event.data().serviceRequestId(), event.data().categoryId());
    }
}
