package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.ServiceRequestCreatedHandler;
import co.quickpatch.matching.application.events.TechnicianEventsHandler;
import co.quickpatch.matching.application.technicians.TechnicianAvailabilityService;
import co.quickpatch.matching.application.technicians.TechnicianPorts;
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

    @Bean
    ServiceRequestCreatedHandler serviceRequestCreatedHandler(Ports.TenantTransaction transaction,
            Ports.ProcessedEventStore processedEvents, Ports.MatchingStarter matching) {
        return new ServiceRequestCreatedHandler(transaction, processedEvents, matching);
    }

    @Bean
    TechnicianEventsHandler technicianEventsHandler(Ports.TenantTransaction transaction,
            Ports.ProcessedEventStore processedEvents, Ports.TechnicianProjection projection) {
        return new TechnicianEventsHandler(transaction, processedEvents, projection);
    }

    @Bean
    TechnicianAvailabilityService technicianAvailabilityService(Ports.TenantTransaction transaction,
            TechnicianPorts.AvailabilityStore availability, TechnicianPorts.CoverageStore coverage,
            TechnicianPorts.LocalityCatalog localities, TechnicianPorts.SearchResumer resumer) {
        return new TechnicianAvailabilityService(transaction, availability, coverage, localities, resumer);
    }
}
