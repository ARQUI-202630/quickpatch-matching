package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.ServiceRequestCreatedHandler;
import co.quickpatch.matching.application.events.ServiceRequestCreatedHandler.Outcome;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.InvalidEventException;
import co.quickpatch.matching.domain.events.ServiceRequestCreated;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ServiceRequestCreatedHandlerTests {

    private final UUID tenant = UUID.randomUUID();
    private final List<UUID> tenantsUsados = new ArrayList<>();
    private final Set<UUID> registrados = new HashSet<>();
    private final List<UUID> iniciados = new ArrayList<>();

    private final Ports.TenantTransaction transaccion = new Ports.TenantTransaction() {
        @Override
        public <T> T execute(UUID tenantId, Supplier<T> work) {
            tenantsUsados.add(tenantId);
            return work.get();
        }
    };

    private ServiceRequestCreatedHandler handler() {
        return new ServiceRequestCreatedHandler(transaccion,
                (eventId, tenantId, type) -> registrados.add(eventId),
                event -> iniciados.add(event.data().serviceRequestId()));
    }

    private EventEnvelope<ServiceRequestCreated> evento(ServiceRequestCreated data) {
        return new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCreated.TYPE, 1, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service", data);
    }

    private ServiceRequestCreated datos() {
        return new ServiceRequestCreated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Fuga de agua en el baño", new ServiceRequestCreated.Location(4.65, -74.06), OffsetDateTime.now());
    }

    @Test
    void eventoNuevo_SeAplicaBajoElTenantDelEvento() {
        var evento = evento(datos());

        assertThat(handler().handle(evento)).isEqualTo(Outcome.APPLIED);
        assertThat(tenantsUsados).containsExactly(tenant);
        assertThat(iniciados).containsExactly(evento.data().serviceRequestId());
    }

    @Test
    void mismoEventoDosVeces_SoloIniciaElMatchingUnaVez() {
        var evento = evento(datos());

        handler().handle(evento);
        assertThat(handler().handle(evento)).isEqualTo(Outcome.DUPLICATE);
        assertThat(iniciados).hasSize(1);
    }

    @Test
    void sobreIncompleto_SeRechazaSinAbrirTransaccion() {
        var sinTenant = new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCreated.TYPE, 1, OffsetDateTime.now(),
                UUID.randomUUID(), null, "service-request-service", datos());

        assertThatThrownBy(() -> handler().handle(sinTenant)).isInstanceOf(InvalidEventException.class);
        assertThat(tenantsUsados).isEmpty();
    }

    @Test
    void tipoOVersionDistintos_SeRechazan() {
        var otroTipo = new EventEnvelope<>(UUID.randomUUID(), "otro.evento", 1, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service", datos());
        var otraVersion = new EventEnvelope<>(UUID.randomUUID(), ServiceRequestCreated.TYPE, 2, OffsetDateTime.now(),
                UUID.randomUUID(), tenant, "service-request-service", datos());

        assertThatThrownBy(() -> handler().handle(otroTipo)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler().handle(otraVersion)).isInstanceOf(InvalidEventException.class);
        assertThat(iniciados).isEmpty();
    }

    @Test
    void datosFueraDelContrato_SeRechazan() {
        var base = datos();
        var sinUbicacion = new ServiceRequestCreated(base.serviceRequestId(), base.clientId(), base.categoryId(),
                base.description(), null, base.createdAt());
        var descripcionCorta = new ServiceRequestCreated(base.serviceRequestId(), base.clientId(), base.categoryId(),
                "corta", base.location(), base.createdAt());
        var latitudInvalida = new ServiceRequestCreated(base.serviceRequestId(), base.clientId(), base.categoryId(),
                base.description(), new ServiceRequestCreated.Location(95.0, -74.0), base.createdAt());

        for (var data : List.of(sinUbicacion, descripcionCorta, latitudInvalida)) {
            assertThatThrownBy(() -> handler().handle(evento(data))).isInstanceOf(InvalidEventException.class);
        }
        assertThat(iniciados).isEmpty();
    }

    @Test
    void eventoNulo_LanzaExcepcion() {
        assertThatThrownBy(() -> handler().handle(null)).isInstanceOf(NullPointerException.class);
    }
}
