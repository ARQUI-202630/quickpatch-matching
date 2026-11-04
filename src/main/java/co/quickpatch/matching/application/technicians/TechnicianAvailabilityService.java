package co.quickpatch.matching.application.technicians;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import co.quickpatch.matching.domain.technicians.Technicians;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.Coverage;
import co.quickpatch.matching.domain.technicians.Technicians.InvalidTransitionException;
import co.quickpatch.matching.domain.technicians.Technicians.Locality;
import co.quickpatch.matching.domain.technicians.Technicians.TechnicianNotFoundException;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Disponibilidad y cobertura del técnico autenticado (RF-13, DD 8.3.6, contrato matching 1.0.0). El tenant y el
 * técnico salen del token; todo corre en una transacción con el tenant fijado, así que RLS impide tocar datos de
 * otro tenant. Si Matching aún no tiene la fila del técnico (no procesó {@code technician.registered}), responde
 * que no existe.
 */
public class TechnicianAvailabilityService {

    private final Ports.TenantTransaction transaction;
    private final TechnicianPorts.AvailabilityStore availability;
    private final TechnicianPorts.CoverageStore coverage;
    private final TechnicianPorts.LocalityCatalog localities;
    private final TechnicianPorts.SearchResumer resumer;

    public TechnicianAvailabilityService(Ports.TenantTransaction transaction,
            TechnicianPorts.AvailabilityStore availability, TechnicianPorts.CoverageStore coverage,
            TechnicianPorts.LocalityCatalog localities, TechnicianPorts.SearchResumer resumer) {
        this.transaction = Objects.requireNonNull(transaction);
        this.availability = Objects.requireNonNull(availability);
        this.coverage = Objects.requireNonNull(coverage);
        this.localities = Objects.requireNonNull(localities);
        this.resumer = Objects.requireNonNull(resumer);
    }

    public Availability getAvailability(UUID tenantId, UUID technicianId) {
        return transaction.execute(tenantId,
                () -> availability.find(technicianId).orElseThrow(TechnicianNotFoundException::new));
    }

    /**
     * Cambia entre {@code disponible} y {@code no_disponible}. Un técnico {@code ocupado} no puede cambiarse
     * (RN-M6); repetir el estado actual no es un error y no cambia nada. Pasar a {@code disponible} reintenta las
     * solicitudes en espera (RN-M10).
     */
    public Availability updateAvailability(UUID tenantId, UUID technicianId, String status) {
        if (status == null || !Technicians.SELECTABLE.contains(status)) {
            throw new ValidationException("status", "El estado debe ser disponible o no_disponible.");
        }
        return transaction.execute(tenantId, () -> {
            var current = availability.findForUpdate(technicianId).orElseThrow(TechnicianNotFoundException::new);
            if (Technicians.OCUPADO.equals(current.status())) {
                throw new InvalidTransitionException("El técnico está ocupado con un servicio.");
            }
            if (current.status().equals(status)) {
                return current;
            }
            var updated = availability.update(technicianId, status);
            if (Technicians.DISPONIBLE.equals(status)) {
                resumer.technicianMayBeCandidate(tenantId, technicianId, ResumeTrigger.AVAILABILITY);
            }
            return updated;
        });
    }

    public Coverage getCoverage(UUID tenantId, UUID technicianId) {
        return transaction.execute(tenantId, () -> {
            availability.find(technicianId).orElseThrow(TechnicianNotFoundException::new);
            return new Coverage(coverage.list(technicianId));
        });
    }

    /**
     * Reemplaza la cobertura completa (DD 5.7): una lista vacía, con repetidos o con un código que no existe se
     * rechaza antes de escribir. Cambiar la cobertura reintenta las solicitudes en espera (RN-M10).
     */
    public Coverage replaceCoverage(UUID tenantId, UUID technicianId, List<String> localityCodes) {
        var codes = validate(localityCodes);
        return transaction.execute(tenantId, () -> {
            availability.findForUpdate(technicianId).orElseThrow(TechnicianNotFoundException::new);
            coverage.replace(tenantId, technicianId, codes);
            resumer.technicianMayBeCandidate(tenantId, technicianId, ResumeTrigger.COVERAGE);
            return new Coverage(coverage.list(technicianId));
        });
    }

    public List<Locality> listLocalities() {
        return localities.all();
    }

    private List<String> validate(List<String> localityCodes) {
        if (localityCodes == null || localityCodes.isEmpty()) {
            throw new ValidationException("localityCodes", "Elige al menos una localidad.");
        }
        if (localityCodes.size() > Technicians.MAX_LOCALITIES) {
            throw new ValidationException("localityCodes", "Bogotá tiene 20 localidades; la lista no puede ser más larga.");
        }
        if (localityCodes.stream().anyMatch(Objects::isNull) || new HashSet<>(localityCodes).size() != localityCodes.size()) {
            throw new ValidationException("localityCodes", "La lista no puede tener códigos vacíos ni repetidos.");
        }
        var known = localities.all().stream().map(Locality::code).collect(Collectors.toSet());
        var unknown = localityCodes.stream().filter(code -> !known.contains(code)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ValidationException("localityCodes", "Localidades que no existen: " + String.join(", ", unknown) + ".");
        }
        return List.copyOf(localityCodes);
    }
}
