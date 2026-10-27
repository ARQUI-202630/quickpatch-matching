package co.quickpatch.matching.api.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.quickpatch.matching.TestTokens;
import co.quickpatch.matching.api.http.ApiExceptionHandler;
import co.quickpatch.matching.api.http.CorrelationIdFilter;
import co.quickpatch.matching.api.security.SecurityConfig;
import co.quickpatch.matching.application.technicians.TechnicianAvailabilityService;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.Coverage;
import co.quickpatch.matching.domain.technicians.Technicians.InvalidTransitionException;
import co.quickpatch.matching.domain.technicians.Technicians.Locality;
import co.quickpatch.matching.domain.technicians.Technicians.TechnicianNotFoundException;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API de Matching con la seguridad real (firma RS256, emisor, audiencia y rol) y el caso de uso simulado: verifica
 * los códigos y los cuerpos del contrato {@code matching.v1.yaml} 1.0.0.
 */
@WebMvcTest(TechniciansController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, CorrelationIdFilter.class})
@TestPropertySource(properties = {
        "quickpatch.jwt.issuer=quickpatch-identity",
        "quickpatch.jwt.audience=quickpatch",
})
class TechniciansApiTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TECNICO = UUID.randomUUID();
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-12T09:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TechnicianAvailabilityService service;

    @org.springframework.test.context.DynamicPropertySource
    static void llave(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("quickpatch.jwt.public-key-pem", () -> TestTokens.PUBLIC_KEY_PEM);
    }

    private static String bearer(String role) {
        return "Bearer " + TestTokens.create(TECNICO, TENANT, role);
    }

    @Test
    void localidades_SonPublicas() throws Exception {
        when(service.listLocalities()).thenReturn(List.of(new Locality("01", "Usaquén"), new Locality("02", "Chapinero")));

        mvc.perform(get("/v1/localities"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$[1].code").value("02"))
                .andExpect(jsonPath("$[1].name").value("Chapinero"));
    }

    @Test
    void tecnico_ConsultaSuDisponibilidadConElTenantYElUsuarioDelToken() throws Exception {
        when(service.getAvailability(TENANT, TECNICO)).thenReturn(new Availability("no_disponible", T0));

        mvc.perform(get("/v1/technicians/me/availability").header("Authorization", bearer("tecnico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_disponible"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void cambiarDisponibilidad_200ConElEstadoNuevo() throws Exception {
        when(service.updateAvailability(TENANT, TECNICO, "disponible")).thenReturn(new Availability("disponible", T0));

        mvc.perform(put("/v1/technicians/me/availability").header("Authorization", bearer("tecnico"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"disponible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disponible"));
    }

    @Test
    void tecnicoOcupado_409TransicionInvalida() throws Exception {
        when(service.updateAvailability(TENANT, TECNICO, "no_disponible"))
                .thenThrow(new InvalidTransitionException("El técnico está ocupado con un servicio."));

        mvc.perform(put("/v1/technicians/me/availability").header("Authorization", bearer("tecnico"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"no_disponible\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/transicion-invalida"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void tecnicoQueMatchingNoConoce_404() throws Exception {
        when(service.getCoverage(TENANT, TECNICO)).thenThrow(new TechnicianNotFoundException());

        mvc.perform(get("/v1/technicians/me/coverage").header("Authorization", bearer("tecnico")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/no-encontrado"));
    }

    @Test
    void coberturaInvalida_400ConErroresPorCampo() throws Exception {
        when(service.replaceCoverage(eq(TENANT), eq(TECNICO), any()))
                .thenThrow(new ValidationException("localityCodes", "Localidades que no existen: 99."));
        var correlacion = UUID.randomUUID();

        mvc.perform(put("/v1/technicians/me/coverage").header("Authorization", bearer("tecnico"))
                        .header(CorrelationIdFilter.HEADER, correlacion.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"localityCodes\":[\"99\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CorrelationIdFilter.HEADER, correlacion.toString()))
                .andExpect(jsonPath("$.correlationId").value(correlacion.toString()))
                .andExpect(jsonPath("$.errors.localityCodes[0]").value("Localidades que no existen: 99."));
    }

    @Test
    void reemplazarCobertura_200ConLasLocalidades() throws Exception {
        when(service.replaceCoverage(TENANT, TECNICO, List.of("02", "12")))
                .thenReturn(new Coverage(List.of(new Locality("02", "Chapinero"), new Locality("12", "Barrios Unidos"))));

        mvc.perform(put("/v1/technicians/me/coverage").header("Authorization", bearer("tecnico"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"localityCodes\":[\"02\",\"12\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localities[1].name").value("Barrios Unidos"));
    }

    @Test
    void cuerpoQueNoEsJson_400() throws Exception {
        mvc.perform(put("/v1/technicians/me/availability").header("Authorization", bearer("tecnico"))
                        .contentType(MediaType.APPLICATION_JSON).content("no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/validacion"));
    }

    @Test
    void sinToken_401ProblemJson() throws Exception {
        mvc.perform(get("/v1/technicians/me/availability"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/no-autenticado"))
                .andExpect(jsonPath("$.correlationId").exists());
        verifyNoInteractions(service);
    }

    @Test
    void tokenVencidoODeOtroEmisor_401() throws Exception {
        var vencido = TestTokens.sign(TECNICO.toString(), TENANT.toString(), "tecnico", "quickpatch-identity",
                Instant.now().minusSeconds(120));
        var otroEmisor = TestTokens.sign(TECNICO.toString(), TENANT.toString(), "tecnico", "otro",
                Instant.now().plusSeconds(600));

        for (var token : List.of(vencido, otroEmisor, "no.es.jwt")) {
            mvc.perform(get("/v1/technicians/me/availability").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    @Test
    void otroRol_403() throws Exception {
        for (var rol : List.of("cliente", "admin_tenant", "proveedor")) {
            mvc.perform(get("/v1/technicians/me/availability").header("Authorization", bearer(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/no-autorizado"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void tokenSinTenantValido_401() throws Exception {
        var token = TestTokens.sign(TECNICO.toString(), "no-uuid", "tecnico", "quickpatch-identity",
                Instant.now().plusSeconds(600));

        mvc.perform(get("/v1/technicians/me/availability").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rutaNoDefinida_EsDenegada() throws Exception {
        mvc.perform(get("/v1/otra").header("Authorization", bearer("tecnico")))
                .andExpect(status().isForbidden());
    }

    @Test
    void elCasoDeUsoRecibeElUsuarioDelToken() throws Exception {
        when(service.getAvailability(any(), any())).thenReturn(new Availability("disponible", T0));

        mvc.perform(get("/v1/technicians/me/availability").header("Authorization", bearer("tecnico")))
                .andExpect(status().isOk());

        verify(service).getAvailability(TENANT, TECNICO);
    }
}
