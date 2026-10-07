package co.quickpatch.matching.domain.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code data} de {@code service-request.created} v1 ({@code contracts/events/service-request.created.v1.json}).
 * La dirección escrita no viaja en el evento (K12, minimización de datos personales).
 */
public record ServiceRequestCreated(
        UUID serviceRequestId,
        UUID clientId,
        UUID categoryId,
        String description,
        Location location,
        OffsetDateTime createdAt) {

    public static final String TYPE = "service-request.created";
    public static final int VERSION = 1;

    /** Coordenadas WGS 84 de la solicitud. */
    public record Location(Double latitude, Double longitude) {
    }

    /** Valida los campos obligatorios y los rangos del contrato. */
    public void validate() {
        if (serviceRequestId == null || clientId == null || categoryId == null || createdAt == null
                || location == null || location.latitude() == null || location.longitude() == null) {
            throw new InvalidEventException("Faltan campos obligatorios de service-request.created.");
        }
        if (description == null || description.length() < 10 || description.length() > 1000) {
            throw new InvalidEventException("La descripción debe tener entre 10 y 1000 caracteres.");
        }
        if (Math.abs(location.latitude()) > 90 || Math.abs(location.longitude()) > 180) {
            throw new InvalidEventException("Coordenadas fuera de rango.");
        }
    }
}
