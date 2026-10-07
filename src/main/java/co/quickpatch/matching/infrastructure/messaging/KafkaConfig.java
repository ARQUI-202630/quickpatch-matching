package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.events.Ports;
import co.quickpatch.matching.application.events.ServiceRequestCreatedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Consumo de eventos (ADR-006, ADR-007). */
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
}
