package co.quickpatch.matching.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.quickpatch.matching.TestTokens;
import co.quickpatch.matching.application.matching.OfferService;
import co.quickpatch.matching.domain.matching.Matching.OfferNotAvailableException;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
import co.quickpatch.matching.domain.matching.Matching.TechnicianNotAvailableException;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ofertas del técnico con la seguridad real (RS256, emisor, audiencia y rol) y el caso de uso simulado: códigos y
 * cuerpos de {@code matching.v1.yaml} 1.0.0 (SCRUM-79).
 */
@WebMvcTest(OffersController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, CorrelationIdFilter.class})
@TestPropertySource(properties = {
        "quickpatch.jwt.issuer=quickpatch-identity",
        "quickpatch.jwt.audience=quickpatch",
})
class OffersApiTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TECNICO = UUID.randomUUID();
    private static final UUID OFERTA = UUID.randomUUID();
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T09:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OfferService service;

    @org.springframework.test.context.DynamicPropertySource
    static void llave(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("quickpatch.jwt.public-key-pem", () -> TestTokens.PUBLIC_KEY_PEM);
    }

    private static String bearer(String role) {
        return "Bearer " + TestTokens.create(TECNICO, TENANT, role);
    }

    @Test
    void listar_DevuelveLaPaginaDelContratoSinDireccion() throws Exception {
        var solicitud = UUID.randomUUID();
        var categoria = UUID.randomUUID();
        when(service.listPending(TENANT, TECNICO, "pendiente", 1, 20)).thenReturn(new OfferService.Page(List.of(
                new PendingOffer(OFERTA, solicitud, categoria, "Fuga de agua en la cocina", "02", "Chapinero",
                        new BigDecimal("1234.5"), T0, T0.plusMinutes(3))), 1, 20, 1));

        mvc.perform(get("/v1/matching/offers?status=pendiente").header("Authorization", bearer("tecnico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].attemptId").value(OFERTA.toString()))
                .andExpect(jsonPath("$.items[0].serviceRequestId").value(solicitud.toString()))
                .andExpect(jsonPath("$.items[0].categoryId").value(categoria.toString()))
                .andExpect(jsonPath("$.items[0].categoryName").doesNotExist())
                .andExpect(jsonPath("$.items[0].description").value("Fuga de agua en la cocina"))
                .andExpect(jsonPath("$.items[0].locality.code").value("02"))
                .andExpect(jsonPath("$.items[0].locality.name").value("Chapinero"))
                .andExpect(jsonPath("$.items[0].distanceMeters").value(1234.5))
                .andExpect(jsonPath("$.items[0].offeredAt").exists())
                .andExpect(jsonPath("$.items[0].expiresAt").exists())
                .andExpect(jsonPath("$.items[0].addressText").doesNotExist())
                .andExpect(jsonPath("$.items[0].location").doesNotExist());
    }

    @Test
    void listar_SinDescripcionNiDistancia_UsaValoresVacios() throws Exception {
        when(service.listPending(any(), any(), any(), anyInt(), anyInt())).thenReturn(new OfferService.Page(List.of(
                new PendingOffer(OFERTA, UUID.randomUUID(), UUID.randomUUID(), null, "02", "Chapinero", null, T0,
                        T0.plusMinutes(3))), 1, 20, 1));

        mvc.perform(get("/v1/matching/offers").header("Authorization", bearer("tecnico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].description").value(""))
                .andExpect(jsonPath("$.items[0].distanceMeters").value(0.0));
        verify(service).listPending(TENANT, TECNICO, null, 1, 20);
    }

    @Test
    void listar_FiltroInvalido_400ConErrores() throws Exception {
        when(service.listPending(any(), any(), eq("aceptado"), anyInt(), anyInt()))
                .thenThrow(new ValidationException("status", "Solo pendientes."));

        mvc.perform(get("/v1/matching/offers?status=aceptado").header("Authorization", bearer("tecnico")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/validacion"))
                .andExpect(jsonPath("$.errors.status").exists());
    }

    @Test
    void listar_PaginaQueNoEsNumero_400() throws Exception {
        mvc.perform(get("/v1/matching/offers?page=uno").header("Authorization", bearer("tecnico")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").exists());
    }

    @Test
    void aceptar_200ConLaRespuesta() throws Exception {
        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/accept").header("Authorization", bearer("tecnico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(OFERTA.toString()))
                .andExpect(jsonPath("$.response").value("aceptado"));
        verify(service).accept(TENANT, TECNICO, OFERTA);
    }

    @Test
    void rechazar_200ConLaRespuesta() throws Exception {
        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/reject").header("Authorization", bearer("tecnico")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("rechazado"));
        verify(service).reject(TENANT, TECNICO, OFERTA);
    }

    @Test
    void aceptar_OfertaNoDisponible_409() throws Exception {
        doThrow(new OfferNotAvailableException()).when(service).accept(TENANT, TECNICO, OFERTA);

        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/accept").header("Authorization", bearer("tecnico")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/oferta-no-disponible"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aceptar_TecnicoNoDisponible_409() throws Exception {
        doThrow(new TechnicianNotAvailableException()).when(service).accept(TENANT, TECNICO, OFERTA);

        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/accept").header("Authorization", bearer("tecnico")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/tecnico-no-disponible"));
    }

    @Test
    void rechazar_OfertaNoDisponible_409() throws Exception {
        doThrow(new OfferNotAvailableException()).when(service).reject(TENANT, TECNICO, OFERTA);

        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/reject").header("Authorization", bearer("tecnico")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/oferta-no-disponible"));
    }

    @Test
    void ofertaConIdQueNoEsUuid_400() throws Exception {
        mvc.perform(post("/v1/matching/offers/no-es-uuid/accept").header("Authorization", bearer("tecnico")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.attemptId").exists());
        verifyNoInteractions(service);
    }

    @Test
    void otroRol_403YSinToken_401() throws Exception {
        mvc.perform(get("/v1/matching/offers").header("Authorization", bearer("cliente")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/no-autorizado"));
        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/accept").header("Authorization", bearer("admin_tenant")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/matching/offers/" + OFERTA + "/reject"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://quickpatch.internal/problems/no-autenticado"));
        verifyNoInteractions(service);
    }

    @Test
    void tokenSinTenantValido_401() throws Exception {
        mvc.perform(get("/v1/matching/offers").header("Authorization",
                        "Bearer " + TestTokens.sign(TECNICO.toString(), "no-uuid", "tecnico", "quickpatch-identity",
                                java.time.Instant.now().plusSeconds(600))))
                .andExpect(status().isUnauthorized());
    }
}
