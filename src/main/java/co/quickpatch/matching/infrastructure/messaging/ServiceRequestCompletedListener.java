package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.events.ServiceRequestCompletedHandler;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.InvalidEventException;
import co.quickpatch.matching.domain.events.ServiceRequestCompleted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor de {@code service-request.completed} (topic con el mismo nombre, SCRUM-94). El offset se confirma solo cuando
 * el listener termina sin error: un fallo de la base se reintenta (ver {@link KafkaConfig}) y un mensaje que no
 * cumple el contrato se registra y se descarta, porque reintentarlo no lo corrige.
 */
@Component
public class ServiceRequestCompletedListener {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceRequestCompletedListener.class);
    private static final TypeReference<EventEnvelope<ServiceRequestCompleted>> TYPE = new TypeReference<>() { };

    private final JsonMapper json;
    private final ServiceRequestCompletedHandler handler;

    public ServiceRequestCompletedListener(JsonMapper json, ServiceRequestCompletedHandler handler) {
        this.json = json;
        this.handler = handler;
    }

    @KafkaListener(topics = ServiceRequestCompleted.TYPE)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope<ServiceRequestCompleted> event;
        try {
            event = json.readValue(record.value(), TYPE);
        } catch (JacksonException | IllegalArgumentException e) {
            LOG.warn("Mensaje descartado en {}-{}@{}: no es JSON válido para el contrato.",
                    record.topic(), record.partition(), record.offset());
            return;
        }
        if (event == null) {
            LOG.warn("Mensaje vacío descartado en {}-{}@{}.", record.topic(), record.partition(), record.offset());
            return;
        }

        MDC.put("correlationId", String.valueOf(event.correlationId()));
        MDC.put("tenantId", String.valueOf(event.tenantId()));
        MDC.put("eventId", String.valueOf(event.eventId()));
        try {
            var outcome = handler.handle(event);
            LOG.info("service-request.completed {}: {}", event.eventId(), outcome);
        } catch (InvalidEventException e) {
            LOG.warn("Evento {} descartado: {}", event.eventId(), e.getMessage());
        } finally {
            MDC.remove("correlationId");
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }
}
