package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.TechnicianEventsHandler;
import co.quickpatch.matching.application.events.TechnicianEventsHandler.Outcome;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.InvalidEventException;
import co.quickpatch.matching.domain.events.TechnicianRegistered;
import co.quickpatch.matching.domain.events.TechnicianVerificationChanged;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TechnicianEventsHandlerTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-12T09:00:00Z");

    private final UUID tenant = UUID.randomUUID();
    private final UUID tecnico = UUID.randomUUID();
    private final UUID especialidad = UUID.randomUUID();
    private final List<UUID> tenantsUsados = new ArrayList<>();
    private final Set<UUID> registrados = new HashSet<>();
    private final Map<UUID, Ports.TechnicianSnapshot> proyeccion = new HashMap<>();

    private final Ports.TenantTransaction transaccion = new Ports.TenantTransaction() {
        @Override
        public <T> T execute(UUID tenantId, Supplier<T> work) {
            tenantsUsados.add(tenantId);
            return work.get();
        }
    };

    /** Misma regla que el SQL: crea la fila o la reemplaza solo si el evento es más reciente. */
    private final Ports.TechnicianProjection proyectar = snapshot -> {
        var actual = proyeccion.get(snapshot.technicianId());
        if (actual != null && !actual.updatedAt().isBefore(snapshot.updatedAt())) {
            return false;
        }
        proyeccion.put(snapshot.technicianId(), snapshot);
        return true;
    };

    private TechnicianEventsHandler handler() {
        return new TechnicianEventsHandler(transaccion, (eventId, tenantId, type) -> registrados.add(eventId), proyectar);
    }

    private <T> EventEnvelope<T> sobre(String tipo, T data) {
        return new EventEnvelope<>(UUID.randomUUID(), tipo, 1, T0, UUID.randomUUID(), tenant, "identity-service", data);
    }

    private EventEnvelope<TechnicianRegistered> registro(OffsetDateTime updatedAt) {
        return sobre(TechnicianRegistered.TYPE,
                new TechnicianRegistered(tecnico, especialidad, null, "pendiente", updatedAt));
    }

    private EventEnvelope<TechnicianVerificationChanged> cambio(String anterior, String nuevo, OffsetDateTime updatedAt) {
        return sobre(TechnicianVerificationChanged.TYPE,
                new TechnicianVerificationChanged(tecnico, especialidad, anterior, nuevo, null, updatedAt));
    }

    @Test
    void registro_CreaLaProyeccionBajoElTenantDelEvento() {
        assertThat(handler().handleRegistered(registro(T0))).isEqualTo(Outcome.APPLIED);

        assertThat(tenantsUsados).containsExactly(tenant);
        var fila = proyeccion.get(tecnico);
        assertThat(fila.tenantId()).isEqualTo(tenant);
        assertThat(fila.specialtyId()).isEqualTo(especialidad);
        assertThat(fila.verificationStatus()).isEqualTo("pendiente");
    }

    @Test
    void suspension_ActualizaLaCopiaDelEstado() {
        handler().handleRegistered(registro(T0));
        handler().handleVerificationChanged(cambio("pendiente", "aprobado", T0.plusHours(1)));

        var resultado = handler().handleVerificationChanged(cambio("aprobado", "suspendido", T0.plusHours(2)));

        assertThat(resultado).isEqualTo(Outcome.APPLIED);
        assertThat(proyeccion.get(tecnico).verificationStatus()).isEqualTo("suspendido");
    }

    @Test
    void cambioAntesQueElRegistro_CreaLaFilaYElRegistroTardioSeDescarta() {
        assertThat(handler().handleVerificationChanged(cambio("pendiente", "aprobado", T0.plusHours(1))))
                .isEqualTo(Outcome.APPLIED);

        assertThat(handler().handleRegistered(registro(T0))).isEqualTo(Outcome.STALE);
        assertThat(proyeccion.get(tecnico).verificationStatus()).isEqualTo("aprobado");
    }

    @Test
    void eventoRepetido_NoSeAplicaDosVeces() {
        var evento = cambio("pendiente", "rechazado", T0.plusHours(1));
        handler().handleRegistered(registro(T0));

        assertThat(handler().handleVerificationChanged(evento)).isEqualTo(Outcome.APPLIED);
        assertThat(handler().handleVerificationChanged(evento)).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void estadoFueraDelContrato_EsInvalido() {
        assertThatThrownBy(() -> handler().handleVerificationChanged(cambio("aprobado", "bloqueado", T0)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler().handleRegistered(sobre(TechnicianRegistered.TYPE,
                new TechnicianRegistered(tecnico, null, null, "pendiente", T0))))
                .isInstanceOf(InvalidEventException.class);
        assertThat(registrados).isEmpty();
        assertThat(proyeccion).isEmpty();
    }

    @Test
    void motivoDeMasDe500Caracteres_EsInvalido() {
        var evento = sobre(TechnicianVerificationChanged.TYPE, new TechnicianVerificationChanged(
                tecnico, especialidad, "aprobado", "suspendido", "x".repeat(501), T0));

        assertThatThrownBy(() -> handler().handleVerificationChanged(evento)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void tipoEquivocado_EsInvalido() {
        var evento = new EventEnvelope<>(UUID.randomUUID(), "technician.otro", 1, T0, UUID.randomUUID(), tenant,
                "identity-service", new TechnicianRegistered(tecnico, especialidad, null, "pendiente", T0));

        assertThatThrownBy(() -> handler().handleRegistered(evento)).isInstanceOf(InvalidEventException.class);
    }
}
