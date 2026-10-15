package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.ServiceRequestCreatedHandler;
import co.quickpatch.matching.application.events.TechnicianEventsHandler;
import co.quickpatch.matching.application.matching.MatchingEngine;
import co.quickpatch.matching.application.matching.MatchingPorts;
import co.quickpatch.matching.application.matching.OfferService;
import co.quickpatch.matching.application.technicians.TechnicianAvailabilityService;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Consumo de eventos (ADR-006, ADR-007) y casos de uso de la aplicación. */
@Configuration
public class KafkaConfig {

    /**
     * Un error al aplicar el evento (por ejemplo, PostgreSQL caído) se reintenta cada 2 segundos sin límite y
     * sin confirmar el offset: el evento no se pierde ni se salta. Los mensajes inválidos no llegan aquí.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }

    /** Motor de matching (RN-M1 a RN-M10); la oferta vence en {@code MATCHING_OFFER_TTL} (RN-M8, 3 minutos). */
    @Bean
    MatchingEngine matchingEngine(MatchingPorts.RequestStore requests, MatchingPorts.CandidateSearch candidates,
            MatchingPorts.AttemptStore attempts, MatchingPorts.Outbox outbox,
            MatchingPorts.CorrelationContext correlation,
            @Value("${quickpatch.matching.offer-ttl:3m}") Duration offerTtl) {
        return new MatchingEngine(requests, candidates, attempts, outbox, correlation, offerTtl);
    }

    /** Ofertas del técnico: listar, aceptar, rechazar y vencer (RF-10, RN-M2 a RN-M8; SCRUM-79). */
    @Bean
    OfferService offerService(Ports.TenantTransaction transaction, MatchingPorts.OfferStore offers,
            MatchingPorts.RequestStore requests, TechnicianPorts.AvailabilityStore availability,
            MatchingPorts.Outbox outbox, MatchingPorts.CorrelationContext correlation, MatchingEngine engine) {
        return new OfferService(transaction, offers, requests, availability, outbox, correlation, engine);
    }

    @Bean
    ServiceRequestCreatedHandler serviceRequestCreatedHandler(Ports.TenantTransaction transaction,
            Ports.ProcessedEventStore processedEvents, Ports.MatchingStarter matching) {
        return new ServiceRequestCreatedHandler(transaction, processedEvents, matching);
    }

    @Bean
    TechnicianEventsHandler technicianEventsHandler(Ports.TenantTransaction transaction,
            Ports.ProcessedEventStore processedEvents, Ports.TechnicianProjection projection,
            TechnicianPorts.SearchResumer resumer) {
        return new TechnicianEventsHandler(transaction, processedEvents, projection, resumer);
    }

    @Bean
    TechnicianAvailabilityService technicianAvailabilityService(Ports.TenantTransaction transaction,
            TechnicianPorts.AvailabilityStore availability, TechnicianPorts.CoverageStore coverage,
            TechnicianPorts.LocalityCatalog localities, TechnicianPorts.SearchResumer resumer) {
        return new TechnicianAvailabilityService(transaction, availability, coverage, localities, resumer);
    }
}
