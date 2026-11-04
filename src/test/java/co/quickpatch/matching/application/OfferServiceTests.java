package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.matching.MatchingEngine;
import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.application.matching.OfferService;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.events.MatchingEvents.NoTechnicianAvailable;
import co.quickpatch.matching.domain.events.MatchingEvents.OfferCreated;
import co.quickpatch.matching.domain.events.MatchingEvents.TechnicianAssigned;
import co.quickpatch.matching.domain.matching.Matching.Candidate;
import co.quickpatch.matching.domain.matching.Matching.MatchingRequest;
import co.quickpatch.matching.domain.matching.Matching.Offer;
import co.quickpatch.matching.domain.matching.Matching.OfferNotAvailableException;
import co.quickpatch.matching.domain.matching.Matching.OfferToAnswer;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
import co.quickpatch.matching.domain.matching.Matching.TechnicianNotAvailableException;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Respuesta del técnico a la oferta (SCRUM-79) con el motor de matching real y almacenes en memoria que siguen las
 * reglas del SQL: una oferta pendiente por solicitud y por técnico, y RN-M9 sin repetir técnico en la solicitud.
 */
class OfferServiceTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T09:00:00Z");

    private final UUID tenant = UUID.randomUUID();
    private final UUID cliente = UUID.randomUUID();
    private final UUID correlacion = UUID.randomUUID();

    private final Map<UUID, MatchingRequest> solicitudes = new LinkedHashMap<>();
    private final Map<UUID, String> disponibilidad = new HashMap<>();
    /** Técnicos que cumplen las condiciones fijas de RN-M9 para cada solicitud, del más cercano al más lejano. */
    private final Map<UUID, List<UUID>> elegibles = new HashMap<>();
    private final List<Intento> intentos = new ArrayList<>();
    private final List<Evento> outbox = new ArrayList<>();
    private UUID tenantDeLaTransaccion;

    private static final class Intento {
        final UUID id = UUID.randomUUID();
        final UUID solicitud;
        final UUID tecnico;
        String respuesta = "pendiente";
        boolean vencido;
        OffsetDateTime respondidoEn;

        Intento(UUID solicitud, UUID tecnico) {
            this.solicitud = solicitud;
            this.tecnico = tecnico;
        }
    }

    private record Evento(UUID tenantId, UUID aggregateId, String type, Object data, UUID correlationId) {
    }

    private final Ports.TenantTransaction transaccion = new Ports.TenantTransaction() {
        @Override
        public <T> T execute(UUID tenantId, Supplier<T> work) {
            tenantDeLaTransaccion = tenantId;
            try {
                return work.get();
            } finally {
                tenantDeLaTransaccion = null;
            }
        }
    };

    private final class Almacen implements MatchingPorts.RequestStore, MatchingPorts.CandidateSearch,
            MatchingPorts.AttemptStore, MatchingPorts.OfferStore {

        @Override
        public boolean create(MatchingRequest request) {
            return solicitudes.putIfAbsent(request.serviceRequestId(), request) == null;
        }

        @Override
        public Optional<MatchingRequest> findForUpdate(UUID serviceRequestId) {
            return Optional.ofNullable(solicitudes.get(serviceRequestId)).filter(r -> r.tenantId().equals(tenantDeLaTransaccion));
        }

        @Override
        public void updateStatus(UUID serviceRequestId, String status) {
            var r = solicitudes.get(serviceRequestId);
            solicitudes.put(serviceRequestId, new MatchingRequest(r.serviceRequestId(), r.tenantId(), r.clientId(),
                    r.categoryId(), r.latitude(), r.longitude(), status, r.createdAt(), r.description()));
        }

        @Override
        public Optional<MatchingRequest> oldestWaitingFor(UUID technicianId) {
            return Optional.empty();
        }

        @Override
        public List<MatchingRequest> waiting(int limit) {
            return List.of();
        }

        @Override
        public List<Candidate> find(UUID serviceRequestId, int limit) {
            return elegibles.getOrDefault(serviceRequestId, List.of()).stream()
                    .filter(t -> "disponible".equals(disponibilidad.get(t)))
                    .filter(t -> intentos.stream().noneMatch(i -> i.tecnico.equals(t)
                            && ("pendiente".equals(i.respuesta) || i.solicitud.equals(serviceRequestId))))
                    .limit(limit).map(t -> new Candidate(t, BigDecimal.TEN)).toList();
        }

        @Override
        public boolean hasPendingOffer(UUID serviceRequestId) {
            return intentos.stream().anyMatch(i -> i.solicitud.equals(serviceRequestId) && "pendiente".equals(i.respuesta));
        }

        @Override
        public Optional<Offer> offer(MatchingRequest request, Candidate candidate, Duration ttl) {
            var intento = new Intento(request.serviceRequestId(), candidate.technicianId());
            intentos.add(intento);
            return Optional.of(new Offer(intento.id, intento.solicitud, intento.tecnico, T0.plus(ttl)));
        }

        @Override
        public int count(UUID serviceRequestId) {
            return (int) intentos.stream().filter(i -> i.solicitud.equals(serviceRequestId)).count();
        }

        @Override
        public List<PendingOffer> pendingFor(UUID technicianId, int offset, int limit) {
            return intentos.stream()
                    .filter(i -> i.tecnico.equals(technicianId) && "pendiente".equals(i.respuesta) && !i.vencido)
                    .skip(offset).limit(limit)
                    .map(i -> new PendingOffer(i.id, i.solicitud, UUID.randomUUID(), "Fuga de agua en la cocina", "02",
                            "Chapinero", BigDecimal.TEN, T0, T0.plusMinutes(3)))
                    .toList();
        }

        @Override
        public int countPendingFor(UUID technicianId) {
            return pendingFor(technicianId, 0, Integer.MAX_VALUE).size();
        }

        @Override
        public Optional<OfferToAnswer> findOfferForUpdate(UUID attemptId) {
            return intentos.stream().filter(i -> i.id.equals(attemptId))
                    .filter(i -> solicitudes.get(i.solicitud).tenantId().equals(tenantDeLaTransaccion))
                    .findFirst().map(i -> new OfferToAnswer(i.id, i.solicitud, i.tecnico, i.respuesta, i.vencido));
        }

        @Override
        public OffsetDateTime respond(UUID attemptId, String response) {
            var intento = intentos.stream().filter(i -> i.id.equals(attemptId)).findFirst().orElseThrow();
            intento.respuesta = response;
            intento.respondidoEn = T0.plusMinutes(1);
            return intento.respondidoEn;
        }

        @Override
        public List<OfferToAnswer> expiredForUpdate(int limit) {
            return intentos.stream().filter(i -> "pendiente".equals(i.respuesta) && i.vencido).limit(limit)
                    .map(i -> new OfferToAnswer(i.id, i.solicitud, i.tecnico, i.respuesta, true)).toList();
        }
    }

    private final Almacen almacen = new Almacen();

    private final TechnicianPorts.AvailabilityStore tecnicos = new TechnicianPorts.AvailabilityStore() {
        @Override
        public Optional<Availability> find(UUID technicianId) {
            return Optional.ofNullable(disponibilidad.get(technicianId)).map(s -> new Availability(s, T0));
        }

        @Override
        public Optional<Availability> findForUpdate(UUID technicianId) {
            return find(technicianId);
        }

        @Override
        public Availability update(UUID technicianId, String status) {
            disponibilidad.put(technicianId, status);
            return new Availability(status, T0);
        }
    };

    private final MatchingPorts.Outbox eventos =
            (tenantId, aggregateId, type, data, correlationId) -> outbox.add(new Evento(tenantId, aggregateId, type, data, correlationId));

    private final MatchingEngine motor =
            new MatchingEngine(almacen, almacen, almacen, eventos, () -> correlacion, Duration.ofMinutes(3));

    private final OfferService servicio =
            new OfferService(transaccion, almacen, almacen, tecnicos, eventos, () -> correlacion, motor);

    /** Solicitud buscando técnico con estos candidatos, ya ofrecida al primero. Devuelve el id de la oferta. */
    private UUID ofrecida(UUID solicitud, UUID... candidatos) {
        for (var tecnico : candidatos) {
            disponibilidad.putIfAbsent(tecnico, "disponible");
        }
        solicitudes.put(solicitud, new MatchingRequest(solicitud, tenant, cliente, UUID.randomUUID(), 4.65, -74.06,
                "buscando", T0, "Fuga de agua en la cocina"));
        elegibles.put(solicitud, List.of(candidatos));
        transaccion.execute(tenant, () -> motor.offerNext(solicitud, correlacion));
        return intentos.getLast().id;
    }

    @Test
    void aceptar_OcupaAlTecnicoAsignaLaSolicitudYPublicaElEvento() {
        var solicitud = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        var oferta = ofrecida(solicitud, tecnico);
        outbox.clear();

        servicio.accept(tenant, tecnico, oferta);

        assertThat(intentos.getLast().respuesta).isEqualTo("aceptado");
        assertThat(disponibilidad.get(tecnico)).isEqualTo("ocupado");
        assertThat(solicitudes.get(solicitud).status()).isEqualTo("asignado");
        var evento = outbox.getFirst();
        assertThat(evento.type()).isEqualTo(TechnicianAssigned.TYPE);
        assertThat(evento.aggregateId()).isEqualTo(solicitud);
        assertThat(evento.tenantId()).isEqualTo(tenant);
        assertThat(evento.correlationId()).isEqualTo(correlacion);
        assertThat(evento.data()).isEqualTo(
                new TechnicianAssigned(solicitud, cliente, oferta, tecnico, T0.plusMinutes(1)));
    }

    @Test
    void aceptarOfertaAjenaInexistenteOVencida_NoEstaDisponible() {
        var tecnico = UUID.randomUUID();
        var oferta = ofrecida(UUID.randomUUID(), tecnico);

        assertThatThrownBy(() -> servicio.accept(tenant, UUID.randomUUID(), oferta))
                .isInstanceOf(OfferNotAvailableException.class);
        assertThatThrownBy(() -> servicio.accept(tenant, tecnico, UUID.randomUUID()))
                .isInstanceOf(OfferNotAvailableException.class);
        assertThatThrownBy(() -> servicio.accept(UUID.randomUUID(), tecnico, oferta))
                .isInstanceOf(OfferNotAvailableException.class);

        intentos.getLast().vencido = true;
        assertThatThrownBy(() -> servicio.accept(tenant, tecnico, oferta)).isInstanceOf(OfferNotAvailableException.class);
        assertThat(disponibilidad.get(tecnico)).isEqualTo("disponible");
    }

    @Test
    void aceptarDosVeces_LaSegundaNoEstaDisponible() {
        var tecnico = UUID.randomUUID();
        var oferta = ofrecida(UUID.randomUUID(), tecnico);
        servicio.accept(tenant, tecnico, oferta);

        assertThatThrownBy(() -> servicio.accept(tenant, tecnico, oferta)).isInstanceOf(OfferNotAvailableException.class);
        assertThatThrownBy(() -> servicio.reject(tenant, tecnico, oferta)).isInstanceOf(OfferNotAvailableException.class);
    }

    @Test
    void tecnicoQueDejoDeEstarDisponible_NoPuedeAceptar() {
        var tecnico = UUID.randomUUID();
        var solicitud = UUID.randomUUID();
        var oferta = ofrecida(solicitud, tecnico);
        disponibilidad.put(tecnico, "no_disponible");

        assertThatThrownBy(() -> servicio.accept(tenant, tecnico, oferta))
                .isInstanceOf(TechnicianNotAvailableException.class);
        assertThat(intentos.getLast().respuesta).isEqualTo("pendiente");
        assertThat(solicitudes.get(solicitud).status()).isEqualTo("buscando");
    }

    @Test
    void solicitudQueYaNoBusca_LaOfertaNoEstaDisponible() {
        var tecnico = UUID.randomUUID();
        var solicitud = UUID.randomUUID();
        var oferta = ofrecida(solicitud, tecnico);
        almacen.updateStatus(solicitud, "cancelado");

        assertThatThrownBy(() -> servicio.accept(tenant, tecnico, oferta)).isInstanceOf(OfferNotAvailableException.class);
        assertThat(disponibilidad.get(tecnico)).isEqualTo("disponible");
    }

    @Test
    void rechazar_OfreceAlSiguienteSinPenalizar() {
        var solicitud = UUID.randomUUID();
        var primero = UUID.randomUUID();
        var segundo = UUID.randomUUID();
        var oferta = ofrecida(solicitud, primero, segundo);
        outbox.clear();

        servicio.reject(tenant, primero, oferta);

        assertThat(intentos.getFirst().respuesta).isEqualTo("rechazado");
        assertThat(intentos.getLast().tecnico).isEqualTo(segundo);
        assertThat(outbox).extracting(Evento::type).containsExactly(OfferCreated.TYPE);
        assertThat(disponibilidad.get(primero)).isEqualTo("disponible");
    }

    @Test
    void rechazarSinMasCandidatos_DejaLaSolicitudEnEspera() {
        var solicitud = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        var oferta = ofrecida(solicitud, tecnico);
        outbox.clear();

        servicio.reject(tenant, tecnico, oferta);

        assertThat(solicitudes.get(solicitud).status()).isEqualTo("en_espera");
        assertThat(outbox).extracting(Evento::type).containsExactly(NoTechnicianAvailable.TYPE);
    }

    @Test
    void vencimiento_ExpiraYOfreceAlSiguiente() {
        var solicitud = UUID.randomUUID();
        var primero = UUID.randomUUID();
        var segundo = UUID.randomUUID();
        ofrecida(solicitud, primero, segundo);
        intentos.getLast().vencido = true;

        var vencidas = transaccion.execute(tenant, () -> servicio.expireOverdue(10));

        assertThat(vencidas).isEqualTo(1);
        assertThat(intentos.getFirst().respuesta).isEqualTo("expirado");
        assertThat(intentos.getLast().tecnico).isEqualTo(segundo);
        assertThat(transaccion.execute(tenant, () -> servicio.expireOverdue(10))).isZero();
    }

    @Test
    void listar_SoloLasPendientesDelTecnicoConPaginacion() {
        var tecnico = UUID.randomUUID();
        ofrecida(UUID.randomUUID(), tecnico);

        var pagina = servicio.listPending(tenant, tecnico, "pendiente", 1, 20);
        assertThat(pagina.items()).hasSize(1);
        assertThat(pagina.totalItems()).isEqualTo(1);
        assertThat(pagina.page()).isEqualTo(1);
        assertThat(pagina.size()).isEqualTo(20);
        assertThat(servicio.listPending(tenant, tecnico, null, 2, 20).items()).isEmpty();
        assertThat(servicio.listPending(tenant, UUID.randomUUID(), null, 1, 20).totalItems()).isZero();
    }

    @Test
    void listar_ParametrosInvalidos() {
        var tecnico = UUID.randomUUID();
        assertThatThrownBy(() -> servicio.listPending(tenant, tecnico, "aceptado", 1, 20))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> servicio.listPending(tenant, tecnico, null, 0, 20))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> servicio.listPending(tenant, tecnico, null, 1, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> servicio.listPending(tenant, tecnico, null, 1, 101))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void constructor_ExigeLosPuertos() {
        assertThatThrownBy(() -> new OfferService(null, almacen, almacen, tecnicos, eventos, () -> correlacion, motor))
                .isInstanceOf(NullPointerException.class);
    }
}
