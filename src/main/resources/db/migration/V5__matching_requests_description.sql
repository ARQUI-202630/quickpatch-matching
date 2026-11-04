-- Descripción del problema en matching_requests (DD 5.17, v5): el técnico la ve en la oferta antes de aceptar
-- (matching.v1.yaml, esquema Offer; SCRUM-79). Llega en data.description de service-request.created. Las
-- solicitudes guardadas antes de esta migración no la tienen, por eso admite nulos.
ALTER TABLE matching_requests ADD COLUMN description text;
ALTER TABLE matching_requests ADD CONSTRAINT ck_matching_requests_description
    CHECK (description IS NULL OR char_length(description) BETWEEN 10 AND 1000);
