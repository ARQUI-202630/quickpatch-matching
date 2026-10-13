package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.matching.MatchingEngine;
import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.MatchingEvents.NoTechnicianAvailable;
import co.quickpatch.matching.domain.events.MatchingEvents.OfferCreated;
import co.quickpatch.matching.domain.events.MatchingEvents.SearchResumed;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;
import co.quickpatch.matching.domain.matching.Matching.Candidate;
import co.quickpatch.matching.domain.matching.Matching.MatchingRequest;
import co.quickpatch.matching.domain.matching.Matching.Offer;
import co.quickpatch.matching.domain.matching.Matching.OfferOutcome;
import co.quickpatch.matching.domain.matching.Matching.ResumeTrigger;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Motor de matching con almacenes en memoria que siguen las mismas reglas que el SQL: RN-M1 y RN-M7 (una oferta
 * pendiente por solicitud y por técnico) y RN-M9 (sin otra oferta pendiente ni una anterior para la solicitud).
 */
class MatchingEngineTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-13T09:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(3);

    private final UUID tenant = UUID.randomUUID();
    private final UUID cliente = UUID.randomUUID();
    private final UUID correlacionDelContexto = UUID.randomUUID();

    private final Map<UUID, MatchingRequest> solicitudes = new LinkedHashMap<>();
    /** Técnicos que cumplen las condiciones fijas de RN-M9 (verificación, disponibilidad, especialidad, cobertura). */
    private final Map<UUID, List<Candidate>> elegibles = new HashMap<>();
    private final List<Intento> intentos = new ArrayList<>();
    /** Técnicos que otra transacción toma justo antes del INSERT (pierden la carrera del índice único). */
    private final Set<UUID> tomadosEnParalelo = new HashSet<>();
    private final List<Evento> outbox = new ArrayList<>();

    private record Intento(UUID id, UUID solicitud, UUID tecnico, String respuesta) {
    }

    private record Evento(UUID tenantId, UUID aggregateId, String type, Object data, UUID correlationId) {
    }

    private final MatchingPorts.RequestStore requests = new MatchingPorts.RequestStore() {
        @Override
        public boolean create(MatchingRequest request) {
            return solicitudes.putIfAbsent(request.serviceRequestId(), request) == null;
        }

        @Override
        public Optional<MatchingRequest> findForUpdate(UUID serviceRequestId) {
            return Optional.ofNullable(solicitudes.get(serviceRequestId));
        }

        @Override
        public void updateStatus(UUID serviceRequestId, String status) {
            var r = solicitudes.get(serviceRequestId);
            solicitudes.put(serviceRequestId, new MatchingRequest(r.serviceRequestId(), r.tenantId(), r.clientId(),
                    r.categoryId(), r.latitude(), r.longitude(), status, r.createdAt()));
        }

        @Override
        public Optional<MatchingRequest> oldestWaitingFor(UUID technicianId) {
            return waiting(Integer.MAX_VALUE).stream()
                    .filter(r -> candidatos(r.serviceRequestId()).stream()
                            .anyMatch(c -> c.technicianId().equals(technicianId)))
                    .findFirst();
        }

        @Override
        public List<MatchingRequest> waiting(int limit) {
            return solicitudes.values().stream().filter(r -> r.status().equals("en_espera"))
                    .sorted(Comparator.comparing(MatchingRequest::createdAt)).limit(limit).toList();
        }
    };

    private final MatchingPorts.CandidateSearch busqueda =
            (serviceRequestId, limit) -> candidatos(serviceRequestId).stream().limit(limit).toList();

    private final MatchingPorts.AttemptStore ofertas = new MatchingPorts.AttemptStore() {
        @Override
        public boolean hasPendingOffer(UUID serviceRequestId) {
            return intentos.stream().anyMatch(i -> i.solicitud().equals(serviceRequestId) && i.respuesta().equals("pendiente"));
        }

        @Override
        public Optional<Offer> offer(MatchingRequest request, Candidate candidate, Duration ttl) {
            if (tomadosEnParalelo.contains(candidate.technicianId()) || hasPendingOffer(request.serviceRequestId())) {
                return Optional.empty();
            }
            var intento = new Intento(UUID.randomUUID(), request.serviceRequestId(), candidate.technicianId(), "pendiente");
            intentos.add(intento);
            return Optional.of(new Offer(intento.id(), intento.solicitud(), intento.tecnico(), T0.plus(ttl)));
        }

        @Override
        public int count(UUID serviceRequestId) {
            return (int) intentos.stream().filter(i -> i.solicitud().equals(serviceRequestId)).count();
        }
    };

    private List<Candidate> candidatos(UUID solicitud) {
        return elegibles.getOrDefault(solicitud, List.of()).stream()
                .filter(c -> intentos.stream().noneMatch(i -> i.tecnico().equals(c.technicianId())
                        && (i.respuesta().equals("pendiente") || i.solicitud().equals(solicitud))))
                .toList();
    }

    private MatchingEngine motor() {
        return new MatchingEngine(requests, busqueda, ofertas,
                (tenantId, aggregateId, type, data, correlationId) ->
                        outbox.add(new Evento(tenantId, aggregateId, type, data, correlationId)),
                () -> correlacionDelContexto, TTL);
    }

    private EventEnvelope<ServiceRequestCreated> creada(UUID solicitud, OffsetDateTime createdAt) {
        return new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCreated.TYPE, 1, createdAt, UUID.randomUUID(),
                tenant, "service-request-service", new ServiceRequestCreated(solicitud, cliente, UUID.randomUUID(),
                "Fuga de agua debajo del lavaplatos", new ServiceRequestCreated.Location(4.6533, -74.0836), createdAt));
    }

    private static Candidate candidato(UUID tecnico, double metros) {
        return new Candidate(tecnico, BigDecimal.valueOf(metros));
    }

    private void enEspera(UUID solicitud, OffsetDateTime createdAt) {
        solicitudes.put(solicitud, new MatchingRequest(solicitud, tenant, cliente, UUID.randomUUID(), 4.65, -74.08,
                "en_espera", createdAt));
    }

    @Test
    void solicitudNueva_SeGuardaYSeOfreceAlMasCercano() {
        var solicitud = UUID.randomUUID();
        var cercano = UUID.randomUUID();
        elegibles.put(solicitud, List.of(candidato(cercano, 850.5), candidato(UUID.randomUUID(), 2400)));
        var evento = creada(solicitud, T0);

        motor().start(evento);

        var guardada = solicitudes.get(solicitud);
        assertThat(guardada.status()).isEqualTo("buscando");
        assertThat(guardada.tenantId()).isEqualTo(tenant);
        assertThat(guardada.clientId()).isEqualTo(cliente);
        assertThat(guardada.latitude()).isEqualTo(4.6533);
        assertThat(outbox).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(OfferCreated.TYPE);
            assertThat(e.aggregateId()).isEqualTo(solicitud);
            assertThat(e.tenantId()).isEqualTo(tenant);
            assertThat(e.correlationId()).isEqualTo(evento.correlationId());
            var oferta = (OfferCreated) e.data();
            assertThat(oferta.technicianId()).isEqualTo(cercano);
            assertThat(oferta.expiresAt()).isEqualTo(T0.plusMinutes(3));
            assertThat(oferta.attemptId()).isEqualTo(intentos.getFirst().id());
        });
    }

    @Test
    void solicitudQueYaExistia_NoSeVuelveAOfrecer() {
        var solicitud = UUID.randomUUID();
        elegibles.put(solicitud, List.of(candidato(UUID.randomUUID(), 10)));
        motor().start(creada(solicitud, T0));

        motor().start(creada(solicitud, T0));

        assertThat(intentos).hasSize(1);
        assertThat(outbox).hasSize(1);
    }

    @Test
    void sinCandidatos_QuedaEnEsperaYAvisaSinRelajarElCriterio() {
        var solicitud = UUID.randomUUID();

        motor().start(creada(solicitud, T0));

        assertThat(solicitudes.get(solicitud).status()).isEqualTo("en_espera");
        assertThat(outbox).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(NoTechnicianAvailable.TYPE);
            assertThat(e.data()).isEqualTo(new NoTechnicianAvailable(solicitud, cliente, 0));
        });
    }

    @Test
    void candidatoTomadoEnParalelo_SeOfreceAlSiguiente() {
        var solicitud = UUID.randomUUID();
        var tomado = UUID.randomUUID();
        var siguiente = UUID.randomUUID();
        elegibles.put(solicitud, List.of(candidato(tomado, 100), candidato(siguiente, 200)));
        tomadosEnParalelo.add(tomado);

        motor().start(creada(solicitud, T0));

        assertThat(((OfferCreated) outbox.getFirst().data()).technicianId()).isEqualTo(siguiente);
    }

    @Test
    void trasUnRechazo_SeOfreceAlSiguienteYAlAgotarseQuedaEnEspera() {
        var solicitud = UUID.randomUUID();
        var primero = UUID.randomUUID();
        var segundo = UUID.randomUUID();
        elegibles.put(solicitud, List.of(candidato(primero, 100), candidato(segundo, 200)));
        motor().start(creada(solicitud, T0));
        rechazar(primero);
        var correlacion = UUID.randomUUID();

        assertThat(motor().offerNext(solicitud, correlacion)).isEqualTo(OfferOutcome.OFFERED);
        assertThat(((OfferCreated) outbox.getLast().data()).technicianId()).isEqualTo(segundo);
        assertThat(outbox.getLast().correlationId()).isEqualTo(correlacion);

        rechazar(segundo);
        assertThat(motor().offerNext(solicitud, correlacion)).isEqualTo(OfferOutcome.WAITING);
        assertThat(outbox.getLast().data()).isEqualTo(new NoTechnicianAvailable(solicitud, cliente, 2));
    }

    @Test
    void conOfertaPendienteOSinBuscar_NoHaceNada() {
        var solicitud = UUID.randomUUID();
        elegibles.put(solicitud, List.of(candidato(UUID.randomUUID(), 1), candidato(UUID.randomUUID(), 2)));
        motor().start(creada(solicitud, T0));
        var esperando = UUID.randomUUID();
        enEspera(esperando, T0);

        assertThat(motor().offerNext(solicitud, UUID.randomUUID())).isEqualTo(OfferOutcome.UNCHANGED);
        assertThat(motor().offerNext(esperando, UUID.randomUUID())).isEqualTo(OfferOutcome.UNCHANGED);
        assertThat(motor().offerNext(UUID.randomUUID(), UUID.randomUUID())).isEqualTo(OfferOutcome.UNCHANGED);
        assertThat(intentos).hasSize(1);
        assertThat(outbox).hasSize(1);
    }

    @Test
    void tecnicoQuePuedeSerCandidato_ReanudaLaSolicitudMasAntiguaYSeLaOfrece() {
        var tecnico = UUID.randomUUID();
        var antigua = UUID.randomUUID();
        var reciente = UUID.randomUUID();
        enEspera(reciente, T0.plusMinutes(10));
        enEspera(antigua, T0);
        elegibles.put(antigua, List.of(candidato(tecnico, 300)));
        elegibles.put(reciente, List.of(candidato(tecnico, 50)));

        motor().technicianMayBeCandidate(tenant, tecnico, ResumeTrigger.AVAILABILITY);

        assertThat(outbox).extracting(Evento::type).containsExactly(SearchResumed.TYPE, OfferCreated.TYPE);
        assertThat(outbox.getFirst().data()).isEqualTo(new SearchResumed(antigua, cliente, "disponibilidad"));
        assertThat(outbox).extracting(Evento::correlationId).containsOnly(correlacionDelContexto);
        assertThat(((OfferCreated) outbox.getLast().data()).serviceRequestId()).isEqualTo(antigua);
        assertThat(solicitudes.get(antigua).status()).isEqualTo("buscando");
        assertThat(solicitudes.get(reciente).status()).isEqualTo("en_espera");
    }

    @Test
    void tecnicoQueNoPuedeAtenderNingunaSolicitudEnEspera_NoReanudaNada() {
        enEspera(UUID.randomUUID(), T0);

        motor().technicianMayBeCandidate(tenant, UUID.randomUUID(), ResumeTrigger.COVERAGE);

        assertThat(outbox).isEmpty();
    }

    @Test
    void barrido_ReanudaSoloLasSolicitudesQueYaTienenCandidato() {
        var conCandidato = UUID.randomUUID();
        var sinCandidato = UUID.randomUUID();
        enEspera(sinCandidato, T0);
        enEspera(conCandidato, T0.plusMinutes(1));
        elegibles.put(conCandidato, List.of(candidato(UUID.randomUUID(), 10)));

        assertThat(motor().sweepWaiting(20)).isEqualTo(1);

        assertThat(outbox.getFirst().data()).isEqualTo(new SearchResumed(conCandidato, cliente, "barrido"));
        assertThat(outbox.getLast().type()).isEqualTo(OfferCreated.TYPE);
        assertThat(solicitudes.get(sinCandidato).status()).isEqualTo("en_espera");
    }

    @Test
    void vencimientoDeOfertaQueNoEsPositivo_NoSeAcepta() {
        for (var ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new MatchingEngine(requests, busqueda, ofertas, (a, b, c, d, e) -> { },
                    UUID::randomUUID, ttl)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void disparadores_UsanLosValoresDelContrato() {
        assertThat(List.of(ResumeTrigger.values())).extracting(ResumeTrigger::value)
                .containsExactly("disponibilidad", "cobertura", "aprobacion", "liberacion", "barrido");
    }

    private void rechazar(UUID tecnico) {
        for (var i = 0; i < intentos.size(); i++) {
            var intento = intentos.get(i);
            if (intento.tecnico().equals(tecnico) && intento.respuesta().equals("pendiente")) {
                intentos.set(i, new Intento(intento.id(), intento.solicitud(), intento.tecnico(), "rechazado"));
            }
        }
    }
}
