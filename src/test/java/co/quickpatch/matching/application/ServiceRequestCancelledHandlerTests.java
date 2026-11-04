package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.ServiceRequestCancelledHandler;
import co.quickpatch.matching.application.events.ServiceRequestCancelledHandler.Outcome;
import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.InvalidEventException;
import co.quickpatch.matching.domain.events.ServiceRequestCancelled;
import co.quickpatch.matching.domain.matching.Matching.MatchingRequest;
import co.quickpatch.matching.domain.matching.Matching.OfferToAnswer;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
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

/** {@code service-request.cancelled} en Matching (RN-M5, RN-M6; SCRUM-476). */
class ServiceRequestCancelledHandlerTests {

    private final UUID tenant = UUID.randomUUID();
    private final UUID solicitud = UUID.randomUUID();
    private final List<UUID> tenantsUsados = new ArrayList<>();
    private final Set<UUID> registrados = new HashSet<>();
    private final Map<UUID, String> estadoSolicitud = new HashMap<>();
    private final Map<UUID, String> respuestas = new HashMap<>();
    private final Map<UUID, String> disponibilidad = new HashMap<>();
    private final List<String> reanudados = new ArrayList<>();
    private OfferToAnswer pendiente;
    private UUID aceptado;

    private final Ports.TenantTransaction transaccion = new Ports.TenantTransaction() {
        @Override
        public <T> T execute(UUID tenantId, Supplier<T> work) {
            tenantsUsados.add(tenantId);
            return work.get();
        }
    };

    private final MatchingPorts.RequestStore solicitudes = new MatchingPorts.RequestStore() {
        @Override
        public boolean create(MatchingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MatchingRequest> findForUpdate(UUID serviceRequestId) {
            return Optional.ofNullable(estadoSolicitud.get(serviceRequestId)).map(estado -> new MatchingRequest(
                    serviceRequestId, tenant, UUID.randomUUID(), UUID.randomUUID(), 4.65, -74.06, estado,
                    OffsetDateTime.now(), "Fuga de agua en el baño"));
        }

        @Override
        public void updateStatus(UUID serviceRequestId, String status) {
            estadoSolicitud.put(serviceRequestId, status);
        }

        @Override
        public Optional<MatchingRequest> oldestWaitingFor(UUID technicianId) {
            return Optional.empty();
        }

        @Override
        public List<MatchingRequest> waiting(int limit) {
            return List.of();
        }
    };

    private final MatchingPorts.CancellationStore cancelaciones = new MatchingPorts.CancellationStore() {
        @Override
        public Optional<OfferToAnswer> pendingOfferForUpdate(UUID serviceRequestId) {
            return Optional.ofNullable(pendiente);
        }

        @Override
        public Optional<UUID> acceptedTechnician(UUID serviceRequestId) {
            return Optional.ofNullable(aceptado);
        }
    };

    private final MatchingPorts.OfferStore ofertas = new MatchingPorts.OfferStore() {
        @Override
        public List<PendingOffer> pendingFor(UUID technicianId, int offset, int limit) {
            return List.of();
        }

        @Override
        public int countPendingFor(UUID technicianId) {
            return 0;
        }

        @Override
        public Optional<OfferToAnswer> findOfferForUpdate(UUID attemptId) {
            return Optional.empty();
        }

        @Override
        public OffsetDateTime respond(UUID attemptId, String response) {
            respuestas.put(attemptId, response);
            return OffsetDateTime.now();
        }

        @Override
        public List<OfferToAnswer> expiredForUpdate(int limit) {
            return List.of();
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

    private ServiceRequestCancelledHandler handler() {
        return new ServiceRequestCancelledHandler(transaccion, (eventId, tenantId, type) -> registrados.add(eventId),
                solicitudes, cancelaciones, ofertas, tecnicos,
                (tenantId, technicianId, trigger) -> reanudados.add(technicianId + ":" + trigger.value()));
    }

    private EventEnvelope<ServiceRequestCancelled> evento(UUID tecnico, String tipo) {
        return new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCancelled.TYPE, 1, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service",
                new ServiceRequestCancelled(solicitud, UUID.randomUUID(), tecnico, tipo, "Ya no necesito el servicio.",
                        OffsetDateTime.now()));
    }

    @Test
    void conOfertaPendiente_LaOfertaYLaBusquedaQuedanCanceladas() {
        estadoSolicitud.put(solicitud, "buscando");
        pendiente = new OfferToAnswer(UUID.randomUUID(), solicitud, UUID.randomUUID(), "pendiente", false);

        assertThat(handler().handle(evento(null, "cliente"))).isEqualTo(Outcome.APPLIED);
        assertThat(tenantsUsados).containsExactly(tenant);
        assertThat(respuestas).containsEntry(pendiente.attemptId(), "cancelado");
        assertThat(estadoSolicitud).containsEntry(solicitud, "cancelado");
        assertThat(reanudados).isEmpty();
    }

    @Test
    void conTecnicoAsignado_VuelveADisponibleYSeLeOfreceLaSiguienteEnEspera() {
        var tecnico = UUID.randomUUID();
        estadoSolicitud.put(solicitud, "asignado");
        aceptado = tecnico;
        disponibilidad.put(tecnico, Technicians.OCUPADO);

        assertThat(handler().handle(evento(tecnico, "admin_tenant"))).isEqualTo(Outcome.APPLIED);
        assertThat(disponibilidad).containsEntry(tecnico, Technicians.DISPONIBLE);
        assertThat(estadoSolicitud).containsEntry(solicitud, "cancelado");
        assertThat(reanudados).containsExactly(tecnico + ":" + ResumeTrigger.RELEASE.value());
    }

    @Test
    void tecnicoQueYaNoEstaOcupado_NoSeCambia() {
        var tecnico = UUID.randomUUID();
        estadoSolicitud.put(solicitud, "asignado");
        aceptado = tecnico;
        disponibilidad.put(tecnico, Technicians.NO_DISPONIBLE);

        handler().handle(evento(tecnico, "cliente"));

        assertThat(disponibilidad).containsEntry(tecnico, Technicians.NO_DISPONIBLE);
        assertThat(reanudados).isEmpty();
    }

    @Test
    void mismoEventoDosVeces_SoloSeAplicaUnaVez() {
        estadoSolicitud.put(solicitud, "en_espera");
        var evento = evento(null, "cliente");

        handler().handle(evento);
        estadoSolicitud.put(solicitud, "en_espera");
        assertThat(handler().handle(evento)).isEqualTo(Outcome.DUPLICATE);
        assertThat(estadoSolicitud).containsEntry(solicitud, "en_espera");
    }

    @Test
    void solicitudYaCancelada_NoCambiaNada() {
        estadoSolicitud.put(solicitud, "cancelado");
        pendiente = new OfferToAnswer(UUID.randomUUID(), solicitud, UUID.randomUUID(), "pendiente", false);

        assertThat(handler().handle(evento(null, "sistema"))).isEqualTo(Outcome.ALREADY_CANCELLED);
        assertThat(respuestas).isEmpty();
    }

    @Test
    void solicitudQueMatchingNoTiene_SeDescarta() {
        assertThat(handler().handle(evento(null, "cliente"))).isEqualTo(Outcome.NOT_FOUND);
        assertThat(estadoSolicitud).isEmpty();
    }

    @Test
    void eventoInvalido_SeRechazaSinAbrirTransaccion() {
        assertThatThrownBy(() -> handler().handle(evento(null, "tecnico"))).isInstanceOf(InvalidEventException.class);
        var sinMotivo = new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCancelled.TYPE, 1, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service",
                new ServiceRequestCancelled(solicitud, UUID.randomUUID(), null, "cliente", "", OffsetDateTime.now()));
        assertThatThrownBy(() -> handler().handle(sinMotivo)).isInstanceOf(InvalidEventException.class);
        assertThat(tenantsUsados).isEmpty();
    }
}
