package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.matching.MatchingPorts;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Correlación de la operación en curso: la que dejó en el MDC el filtro HTTP ({@code X-Correlation-Id}) o el
 * consumidor del evento. Así los eventos que publica Matching siguen la cadena de la petición que los originó.
 */
@Component
public class MdcCorrelationContext implements MatchingPorts.CorrelationContext {

    @Override
    public UUID current() {
        var value = MDC.get("correlationId");
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                // Un valor que no es UUID se reemplaza por uno nuevo.
            }
        }
        return UUID.randomUUID();
    }
}
