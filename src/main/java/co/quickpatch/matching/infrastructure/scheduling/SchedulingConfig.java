package co.quickpatch.matching.infrastructure.scheduling;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tareas periódicas: el publicador del Outbox y el barrido de solicitudes en espera. Se pueden apagar con
 * {@code quickpatch.scheduling.enabled=false}.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "quickpatch.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {
    }
}
