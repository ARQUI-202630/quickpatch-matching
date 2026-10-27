package co.quickpatch.matching.api.controllers;

import co.quickpatch.matching.application.matching.OfferService;
import co.quickpatch.matching.domain.matching.Matching.OfferResponse;
import co.quickpatch.matching.domain.matching.Matching.PendingOffer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ofertas del técnico autenticado ({@code matching.v1.yaml} 1.0.0, SCRUM-79): listar, aceptar y rechazar. Solo el
 * rol {@code tecnico}; el técnico es el claim {@code sub} y el tenant el claim {@code tenant_id} (RN-U3).
 */
@RestController
public class OffersController {

    /** Esquema {@code Locality}. */
    public record LocalityView(String code, String name) {
    }

    /** Esquema {@code Offer}: sin la dirección exacta, que solo recibe el técnico asignado (RN-SR13). */
    public record OfferView(UUID attemptId, UUID serviceRequestId, UUID categoryId, String categoryName,
            String description, LocalityView locality, double distanceMeters, OffsetDateTime offeredAt,
            OffsetDateTime expiresAt) {

        static OfferView from(PendingOffer offer) {
            // Matching todavía no replica los nombres de las categorías (catalog.category-changed): categoryName en null.
            return new OfferView(offer.attemptId(), offer.serviceRequestId(), offer.categoryId(), null,
                    offer.description() == null ? "" : offer.description(),
                    new LocalityView(offer.localityCode(), offer.localityName()),
                    offer.distanceM() == null ? 0 : offer.distanceM().doubleValue(), offer.offeredAt(),
                    offer.expiresAt());
        }
    }

    /** Esquema {@code OfferPage}. */
    public record OfferPageView(List<OfferView> items, int page, int size, int totalItems) {
    }

    /** Esquema {@code OfferResponse}. */
    public record OfferAnswerView(UUID attemptId, String response) {
    }

    private final OfferService service;

    public OffersController(OfferService service) {
        this.service = service;
    }

    @GetMapping("/v1/matching/offers")
    public OfferPageView listOffers(@AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var caller = TechniciansController.Caller.from(token);
        var result = service.listPending(caller.tenantId(), caller.userId(), status, page, size);
        return new OfferPageView(result.items().stream().map(OfferView::from).toList(), result.page(), result.size(),
                result.totalItems());
    }

    @PostMapping("/v1/matching/offers/{attemptId}/accept")
    public OfferAnswerView accept(@AuthenticationPrincipal Jwt token, @PathVariable UUID attemptId) {
        var caller = TechniciansController.Caller.from(token);
        service.accept(caller.tenantId(), caller.userId(), attemptId);
        return new OfferAnswerView(attemptId, OfferResponse.ACCEPTED);
    }

    @PostMapping("/v1/matching/offers/{attemptId}/reject")
    public OfferAnswerView reject(@AuthenticationPrincipal Jwt token, @PathVariable UUID attemptId) {
        var caller = TechniciansController.Caller.from(token);
        service.reject(caller.tenantId(), caller.userId(), attemptId);
        return new OfferAnswerView(attemptId, OfferResponse.REJECTED);
    }
}
