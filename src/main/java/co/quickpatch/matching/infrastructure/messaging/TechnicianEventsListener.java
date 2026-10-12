package co.quickpatch.matching.infrastructure.messaging;

import co.quickpatch.matching.application.events.TechnicianEventsHandler;
import co.quickpatch.matching.domain.events.EventEnvelope;
import co.quickpatch.matching.domain.events.InvalidEventException;
import co.quickpatch.matching.domain.events.TechnicianRegistered;
import co.quickpatch.matching.domain.events.TechnicianVerificationChanged;
import java.util.function.Function;
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
 * Consumidor de {@code technician.registered} y {@code technician.verification-changed} (topics con el mismo
 * nombre), igual que {@link ServiceRequestCreatedListener}: el offset se confirma al terminar sin error, un fallo
 * de la base se reintenta y un mensaje que no cumple el contrato se registra y se descarta.
 */
@Component
public class TechnicianEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(TechnicianEventsListener.class);
    private static final TypeReference<EventEnvelope<TechnicianRegistered>> REGISTERED = new TypeReference<>() { };
    private static final TypeReference<EventEnvelope<TechnicianVerificationChanged>> VERIFICATION =
            new TypeReference<>() { };

    private final JsonMapper json;
    private final TechnicianEventsHandler handler;

    public TechnicianEventsListener(JsonMapper json, TechnicianEventsHandler handler) {
        this.json = json;
        this.handler = handler;
    }

    @KafkaListener(topics = TechnicianRegistered.TYPE)
    public void onRegistered(ConsumerRecord<String, String> record) {
        consume(record, REGISTERED, handler::handleRegistered);
    }

    @KafkaListener(topics = TechnicianVerificationChanged.TYPE)
    public void onVerificationChanged(ConsumerRecord<String, String> record) {
        consume(record, VERIFICATION, handler::handleVerificationChanged);
    }

    private <T> void consume(ConsumerRecord<String, String> record, TypeReference<EventEnvelope<T>> type,
            Function<EventEnvelope<T>, TechnicianEventsHandler.Outcome> apply) {
        EventEnvelope<T> event;
        try {
            event = json.readValue(record.value(), type);
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
            var outcome = apply.apply(event);
            LOG.info("{} {}: {}", record.topic(), event.eventId(), outcome);
        } catch (InvalidEventException e) {
            LOG.warn("Evento {} descartado: {}", event.eventId(), e.getMessage());
        } finally {
            MDC.remove("correlationId");
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }
}
