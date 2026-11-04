package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.ServiceRequestCompletedHandler;
import co.quickpatch.matching.application.events.ServiceRequestCompletedHandler.Outcome;
import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.InvalidEventException;
import co.quickpatch.matching.domain.events.ServiceRequestCompleted;
import co.quickpatch.matching.domain.matching.Matching.OfferToAnswer;
import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import co.quickpatch.matching.domain.technicians.Technicians;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@code service-request.completed} en Matching: el técnico vuelve a disponible (RN-M6; SCRUM-94). */
class ServiceRequestCompletedHandlerTests {

    private final UUID tenant = UUID.randomUUID();
    private final UUID solicitud = UUID.randomUUID();
    private final List<UUID> tenantsUsados = new ArrayList<>();
    private final Set<UUID> registrados = new HashSet<>();
    private final Map<UUID, String> disponibilidad = new HashMap<>();
    private final List<String> reanudados = new ArrayList<>();
    private UUID aceptado;

    private final Ports.TenantTransaction transaccion = new Ports.TenantTransaction() {
        @Override
        public <T> T execute(UUID tenantId, Supplier<T> work) {
            tenantsUsados.add(tenantId);
            return work.get();
        }
    };

    private final MatchingPorts.CancellationStore asignaciones = new MatchingPorts.CancellationStore() {
        @Override
        public Optional<OfferToAnswer> pendingOfferForUpdate(UUID serviceRequestId) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> acceptedTechnician(UUID serviceRequestId) {
            return Optional.ofNullable(aceptado);
        }
    };

    private final TechnicianPorts.AvailabilityStore tecnicos = new TechnicianPorts.AvailabilityStore() {
        @Override
        public Optional<Availability> find(UUID technicianId) {
            return findForUpdate(technicianId);
        }

        @Override
        public Optional<Availability> findForUpdate(UUID technicianId) {
            return Optional.ofNullable(disponibilidad.get(technicianId)).map(s -> new Availability(s, OffsetDateTime.now()));
        }

        @Override
        public Availability update(UUID technicianId, String status) {
            disponibilidad.put(technicianId, status);
            return new Availability(status, OffsetDateTime.now());
        }
    };

    private ServiceRequestCompletedHandler handler() {
        return new ServiceRequestCompletedHandler(transaccion, (eventId, tenantId, type) -> registrados.add(eventId),
                asignaciones, tecnicos, (tenantId, technicianId, trigger) -> reanudados.add(technicianId + ":" + trigger.value()));
    }

    private EventEnvelope<ServiceRequestCompleted> evento(UUID tecnico) {
        return new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCompleted.TYPE, 1, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service",
                new ServiceRequestCompleted(solicitud, UUID.randomUUID(), null, tecnico, UUID.randomUUID(), "185000.00",
                        OffsetDateTime.now()));
    }

    @Test
    void tecnicoOcupado_VuelveADisponibleYSeLeOfreceLaSiguienteEnEspera() {
        var tecnico = UUID.randomUUID();
        aceptado = tecnico;
        disponibilidad.put(tecnico, Technicians.OCUPADO);

        assertThat(handler().handle(evento(tecnico))).isEqualTo(Outcome.RELEASED);
        assertThat(tenantsUsados).containsExactly(tenant);
        assertThat(disponibilidad).containsEntry(tecnico, Technicians.DISPONIBLE);
        assertThat(reanudados).containsExactly(tecnico + ":" + ResumeTrigger.RELEASE.value());
    }

    @Test
    void liberaAlTecnicoQueMatchingAsigno_NoAlDelEvento() {
        var asignado = UUID.randomUUID();
        var otro = UUID.randomUUID();
        aceptado = asignado;
        disponibilidad.put(asignado, Technicians.OCUPADO);
        disponibilidad.put(otro, Technicians.OCUPADO);

        handler().handle(evento(otro));

        assertThat(disponibilidad).containsEntry(asignado, Technicians.DISPONIBLE).containsEntry(otro, Technicians.OCUPADO);
    }

    @Test
    void tecnicoQueYaNoEstaOcupado_NoSeCambia() {
        var tecnico = UUID.randomUUID();
        aceptado = tecnico;
        disponibilidad.put(tecnico, Technicians.NO_DISPONIBLE);

        assertThat(handler().handle(evento(tecnico))).isEqualTo(Outcome.NOT_BUSY);
        assertThat(disponibilidad).containsEntry(tecnico, Technicians.NO_DISPONIBLE);
        assertThat(reanudados).isEmpty();
    }

    @Test
    void solicitudSinOfertaAceptada_SeDescarta() {
        assertThat(handler().handle(evento(UUID.randomUUID()))).isEqualTo(Outcome.NOT_ASSIGNED);
        assertThat(disponibilidad).isEmpty();
    }

    @Test
    void mismoEventoDosVeces_SoloSeAplicaUnaVez() {
        var tecnico = UUID.randomUUID();
        aceptado = tecnico;
        disponibilidad.put(tecnico, Technicians.OCUPADO);
        var evento = evento(tecnico);

        handler().handle(evento);
        disponibilidad.put(tecnico, Technicians.OCUPADO);
        assertThat(handler().handle(evento)).isEqualTo(Outcome.DUPLICATE);
        assertThat(disponibilidad).containsEntry(tecnico, Technicians.OCUPADO);
    }

    @Test
    void eventoInvalido_SeRechazaSinAbrirTransaccion() {
        assertThatThrownBy(() -> handler().handle(evento(null))).isInstanceOf(InvalidEventException.class);
        var otroTipo = new EventEnvelope<>(UUID.randomUUID(), "service-request.cancelled", 1, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service", evento(UUID.randomUUID()).data());
        assertThatThrownBy(() -> handler().handle(otroTipo)).isInstanceOf(InvalidEventException.class);
        assertThat(tenantsUsados).isEmpty();
    }
}
