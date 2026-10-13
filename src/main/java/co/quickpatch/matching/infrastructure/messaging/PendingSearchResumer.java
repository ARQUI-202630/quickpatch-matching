package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.technicians.TechnicianPorts;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación provisional de RN-M10, como {@link PendingMatchingStarter}: deja constancia de que el técnico
 * puede volver a ser candidato. El reintento de las solicitudes en espera ({@code matching_requests},
 * {@code matching.search-resumed}) llega con el algoritmo de matching (SCRUM-76) y reemplaza este adaptador sin
 * cambiar los casos de uso.
 */
@Component
public class PendingSearchResumer implements TechnicianPorts.SearchResumer {

    private static final Logger LOG = LoggerFactory.getLogger(PendingSearchResumer.class);

    @Override
    public void technicianMayBeCandidate(UUID tenantId, UUID technicianId) {
        LOG.info("El técnico {} puede volver a ser candidato; reintento de solicitudes en espera pendiente (RN-M10).",
                technicianId);
    }
}
