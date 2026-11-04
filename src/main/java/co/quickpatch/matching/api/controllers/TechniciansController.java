package co.quickpatch.matching.api.controllers;

import co.quickpatch.matching.application.technicians.TechnicianAvailabilityService;
import co.quickpatch.matching.domain.technicians.Technicians.Availability;
import co.quickpatch.matching.domain.technicians.Technicians.Coverage;
import co.quickpatch.matching.domain.technicians.Technicians.Locality;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de {@code matching.v1.yaml} 1.0.0: localidades (público) y disponibilidad y cobertura del técnico
 * autenticado (rol {@code tecnico}). El técnico es el claim {@code sub} y el tenant el claim {@code tenant_id};
 * nunca vienen del cuerpo ni de la URL (RN-U3).
 */
@RestController
public class TechniciansController {

    /** Cuerpo de {@code PUT /v1/technicians/me/availability}. */
    public record UpdateAvailabilityRequest(String status) {
    }

    /** Cuerpo de {@code PUT /v1/technicians/me/coverage}. */
    public record ReplaceCoverageRequest(List<String> localityCodes) {
    }

    private final TechnicianAvailabilityService service;

    public TechniciansController(TechnicianAvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/v1/localities")
    public List<Locality> listLocalities() {
        return service.listLocalities();
    }

    @GetMapping("/v1/technicians/me/availability")
    public Availability getAvailability(@AuthenticationPrincipal Jwt token) {
        var caller = Caller.from(token);
        return service.getAvailability(caller.tenantId(), caller.userId());
    }

    @PutMapping("/v1/technicians/me/availability")
    public Availability updateAvailability(@AuthenticationPrincipal Jwt token, @RequestBody UpdateAvailabilityRequest body) {
        var caller = Caller.from(token);
        return service.updateAvailability(caller.tenantId(), caller.userId(), body.status());
    }

    @GetMapping("/v1/technicians/me/coverage")
    public Coverage getCoverage(@AuthenticationPrincipal Jwt token) {
        var caller = Caller.from(token);
        return service.getCoverage(caller.tenantId(), caller.userId());
    }

    @PutMapping("/v1/technicians/me/coverage")
    public Coverage replaceCoverage(@AuthenticationPrincipal Jwt token, @RequestBody ReplaceCoverageRequest body) {
        var caller = Caller.from(token);
        return service.replaceCoverage(caller.tenantId(), caller.userId(), body.localityCodes());
    }

    /** Usuario y tenant del token; si alguno no es un UUID, el token no sirve (401). */
    record Caller(UUID userId, UUID tenantId) {

        static Caller from(Jwt token) {
            try {
                return new Caller(UUID.fromString(token.getSubject()), UUID.fromString(token.getClaimAsString("tenant_id")));
            } catch (RuntimeException e) {
                throw new InvalidTokenClaimsException();
            }
        }
    }

    /** El token es válido pero no trae {@code sub} o {@code tenant_id} como UUID. */
    public static class InvalidTokenClaimsException extends RuntimeException {

        InvalidTokenClaimsException() {
            super("El token no trae usuario ni tenant válidos.");
        }
    }
}
