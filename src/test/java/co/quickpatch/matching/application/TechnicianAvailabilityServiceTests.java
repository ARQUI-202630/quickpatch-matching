package co.quickpatch.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.technicians.TechnicianAvailabilityService;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.InvalidTransitionException;
import co.quickpatch.matching.domain.technicians.Technicians.Locality;
import co.quickpatch.matching.domain.technicians.Technicians.TechnicianNotFoundException;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TechnicianAvailabilityServiceTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-12T09:00:00Z");

    private final UUID tenant = UUID.randomUUID();
    private final UUID tecnico = UUID.randomUUID();
    private final List<UUID> tenantsUsados = new ArrayList<>();
    private final Map<UUID, Availability> disponibilidad = new HashMap<>();
    private final Map<UUID, List<String>> cobertura = new HashMap<>();
    private final List<UUID> reintentos = new ArrayList<>();
    private final List<Locality> localidades = IntStream.rangeClosed(1, 20)
            .mapToObj(i -> new Locality("%02d".formatted(i), "Localidad " + i)).toList();

    private final Ports.TenantTransaction transaccion = new Ports.TenantTransaction() {
        @Override
        public <T> T execute(UUID tenantId, Supplier<T> work) {
            tenantsUsados.add(tenantId);
            return work.get();
        }
    };

    private final TechnicianPorts.AvailabilityStore store = new TechnicianPorts.AvailabilityStore() {
        @Override
        public Optional<Availability> find(UUID technicianId) {
            return Optional.ofNullable(disponibilidad.get(technicianId));
        }

        @Override
        public Optional<Availability> findForUpdate(UUID technicianId) {
            return find(technicianId);
        }

        @Override
        public Availability update(UUID technicianId, String status) {
            var nueva = new Availability(status, T0.plusMinutes(5));
            disponibilidad.put(technicianId, nueva);
            return nueva;
        }
    };

    private final TechnicianPorts.CoverageStore zonas = new TechnicianPorts.CoverageStore() {
        @Override
        public List<Locality> list(UUID technicianId) {
            return cobertura.getOrDefault(technicianId, List.of()).stream().sorted()
                    .map(code -> new Locality(code, "Localidad " + Integer.parseInt(code))).toList();
        }

        @Override
        public void replace(UUID tenantId, UUID technicianId, List<String> localityCodes) {
            cobertura.put(technicianId, List.copyOf(localityCodes));
        }
    };

    private TechnicianAvailabilityService service() {
        return new TechnicianAvailabilityService(transaccion, store, zonas, () -> localidades,
                (tenantId, technicianId) -> reintentos.add(technicianId));
    }

    private void conEstado(String status) {
        disponibilidad.put(tecnico, new Availability(status, T0));
    }

    @Test
    void consultar_DevuelveElEstadoBajoElTenantDelToken() {
        conEstado("no_disponible");

        assertThat(service().getAvailability(tenant, tecnico).status()).isEqualTo("no_disponible");
        assertThat(tenantsUsados).containsExactly(tenant);
    }

    @Test
    void tecnicoQueMatchingAunNoConoce_NoExiste() {
        assertThatThrownBy(() -> service().getAvailability(tenant, tecnico)).isInstanceOf(TechnicianNotFoundException.class);
        assertThatThrownBy(() -> service().updateAvailability(tenant, tecnico, "disponible"))
                .isInstanceOf(TechnicianNotFoundException.class);
        assertThatThrownBy(() -> service().getCoverage(tenant, tecnico)).isInstanceOf(TechnicianNotFoundException.class);
        assertThatThrownBy(() -> service().replaceCoverage(tenant, tecnico, List.of("02")))
                .isInstanceOf(TechnicianNotFoundException.class);
    }

    @Test
    void pasarADisponible_CambiaElEstadoYReintentaLasSolicitudesEnEspera() {
        conEstado("no_disponible");

        var nueva = service().updateAvailability(tenant, tecnico, "disponible");

        assertThat(nueva.status()).isEqualTo("disponible");
        assertThat(nueva.updatedAt()).isAfter(T0);
        assertThat(reintentos).containsExactly(tecnico);
    }

    @Test
    void pasarANoDisponible_NoReintenta() {
        conEstado("disponible");

        assertThat(service().updateAvailability(tenant, tecnico, "no_disponible").status()).isEqualTo("no_disponible");
        assertThat(reintentos).isEmpty();
    }

    @Test
    void repetirElEstadoActual_NoEsErrorYNoCambiaNada() {
        conEstado("disponible");

        var actual = service().updateAvailability(tenant, tecnico, "disponible");

        assertThat(actual.updatedAt()).isEqualTo(T0);
        assertThat(reintentos).isEmpty();
    }

    @Test
    void tecnicoOcupado_NoPuedeCambiarSuDisponibilidad() {
        conEstado("ocupado");

        assertThatThrownBy(() -> service().updateAvailability(tenant, tecnico, "no_disponible"))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(disponibilidad.get(tecnico).status()).isEqualTo("ocupado");
    }

    @Test
    void estadoQueElTecnicoNoPuedeElegir_EsValidacion() {
        conEstado("disponible");

        for (var estado : new String[] {"ocupado", "otro", null}) {
            assertThatThrownBy(() -> service().updateAvailability(tenant, tecnico, estado))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errors()).containsKey("status"));
        }
    }

    @Test
    void reemplazarCobertura_GuardaLasLocalidadesYReintenta() {
        conEstado("no_disponible");

        var resultado = service().replaceCoverage(tenant, tecnico, List.of("13", "02", "12"));

        assertThat(resultado.localities()).extracting(Locality::code).containsExactly("02", "12", "13");
        assertThat(service().getCoverage(tenant, tecnico).localities()).hasSize(3);
        assertThat(reintentos).containsExactly(tecnico);
    }

    @Test
    void coberturaSinZonas_EsListaVacia() {
        conEstado("no_disponible");

        assertThat(service().getCoverage(tenant, tecnico).localities()).isEmpty();
    }

    @Test
    void coberturaInvalida_EsValidacionYNoSeEscribe() {
        conEstado("no_disponible");
        var invalidas = new ArrayList<List<String>>();
        invalidas.add(null);
        invalidas.add(List.of());
        invalidas.add(List.of("02", "02"));
        invalidas.add(List.of("02", "99"));
        invalidas.add(IntStream.rangeClosed(1, 21).mapToObj("%02d"::formatted).toList());
        var conNulo = new ArrayList<String>();
        conNulo.add(null);
        invalidas.add(conNulo);

        for (var codigos : invalidas) {
            assertThatThrownBy(() -> service().replaceCoverage(tenant, tecnico, codigos))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errors()).containsKey("localityCodes"));
        }
        assertThat(cobertura).isEmpty();
        assertThat(reintentos).isEmpty();
    }

    @Test
    void localidades_SonElCatalogo() {
        assertThat(service().listLocalities()).hasSize(20).first().isEqualTo(new Locality("01", "Localidad 1"));
    }
}
