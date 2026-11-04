package co.quickpatch.matching.application.technicians;

import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.Locality;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puertos de disponibilidad y cobertura del técnico; los implementa la infraestructura. */
public final class TechnicianPorts {

    private TechnicianPorts() {
    }

    /** Disponibilidad en {@code technician_availability} (DD 5.6), bajo el tenant de la transacción (RLS). */
    public interface AvailabilityStore {
        Optional<Availability> find(UUID technicianId);

        /** Busca y bloquea la fila ({@code FOR UPDATE}) hasta el fin de la transacción. */
        Optional<Availability> findForUpdate(UUID technicianId);

        /** Cambia el estado y {@code updated_at}; devuelve la disponibilidad nueva. */
        Availability update(UUID technicianId, String status);
    }

    /** Zonas de cobertura en {@code coverage_zones} (DD 5.7), bajo el tenant de la transacción (RLS). */
    public interface CoverageStore {
        /** Localidades que cubre el técnico, ordenadas por código. */
        List<Locality> list(UUID technicianId);

        /** Borra las zonas del técnico e inserta las nuevas, con el polígono copiado de {@code localities}. */
        void replace(UUID tenantId, UUID technicianId, List<String> localityCodes);
    }

    /** Catálogo de referencia {@code localities} (DD 5.18), igual para todos los tenants. */
    public interface LocalityCatalog {
        /** Las localidades ordenadas por código, sin polígono. */
        List<Locality> all();
    }

    /**
     * Reintento de las solicitudes en espera cuando un técnico puede volver a ser candidato (RN-M10): pasó a
     * {@code disponible}, cambió su cobertura o fue aprobado. Se ejecuta dentro de la transacción del cambio.
     */
    public interface SearchResumer {
        void technicianMayBeCandidate(UUID tenantId, UUID technicianId, ResumeTrigger trigger);
    }
}
