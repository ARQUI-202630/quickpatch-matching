-- matching_requests (DD 5.17, P-05), matching_attempts (DD 5.8) y outbox_events (DD 5.15): el algoritmo de
-- matching (RF-09, RF-10, RN-M1 a RN-M10) y la publicación de sus eventos con Transactional Outbox (ADR-007).

-- Copia mínima de cada solicitud que Matching debe asignar; permite reintentar las que quedan en espera (RN-M10).
CREATE TABLE matching_requests (
    service_request_id uuid                   PRIMARY KEY,
    tenant_id          uuid                   NOT NULL,
    client_id          uuid                   NOT NULL,
    category_id        uuid                   NOT NULL,
    location           geometry(POINT, 4326)  NOT NULL,
    status             varchar(15)            NOT NULL,
    created_at         timestamptz            NOT NULL,
    updated_at         timestamptz            NOT NULL,
    CONSTRAINT ck_matching_requests_status CHECK (status IN ('buscando', 'en_espera', 'asignado', 'cancelado'))
);

-- Reintento de la solicitud en espera más antigua primero (RN-M10).
CREATE INDEX ix_matching_requests_tenant_status_created ON matching_requests (tenant_id, status, created_at);
CREATE INDEX ix_matching_requests_location ON matching_requests USING gist (location);

-- Cada oferta a un técnico y su respuesta (RF-10).
CREATE TABLE matching_attempts (
    id                 uuid          PRIMARY KEY,
    service_request_id uuid          NOT NULL REFERENCES matching_requests (service_request_id),
    technician_id      uuid          NOT NULL,
    tenant_id          uuid          NOT NULL,
    offered_at         timestamptz   NOT NULL,
    response           varchar(20)   NOT NULL,
    responded_at       timestamptz,
    expires_at         timestamptz   NOT NULL,
    distance_m         numeric(10,1),
    CONSTRAINT ck_matching_attempts_response
        CHECK (response IN ('pendiente', 'aceptado', 'rechazado', 'expirado', 'cancelado')),
    CONSTRAINT ck_matching_attempts_expires CHECK (expires_at > offered_at),
    CONSTRAINT ck_matching_attempts_responded
        CHECK ((response = 'pendiente') = (responded_at IS NULL))
);

CREATE INDEX ix_matching_attempts_request ON matching_attempts (service_request_id);
CREATE INDEX ix_matching_attempts_technician_response ON matching_attempts (technician_id, response);
-- RN-M1: una sola oferta pendiente por solicitud. RN-M7: una sola oferta pendiente por técnico.
CREATE UNIQUE INDEX ux_matching_attempts_pending_request
    ON matching_attempts (service_request_id) WHERE response = 'pendiente';
CREATE UNIQUE INDEX ux_matching_attempts_pending_technician
    ON matching_attempts (technician_id) WHERE response = 'pendiente';
-- Vencimiento de ofertas (RN-M3, RN-M8).
CREATE INDEX ix_matching_attempts_pending_expires
    ON matching_attempts (expires_at) WHERE response = 'pendiente';

-- Eventos por publicar, en la misma transacción que el cambio que los origina (ADR-007).
CREATE TABLE outbox_events (
    id           uuid         PRIMARY KEY,
    tenant_id    uuid         NOT NULL DEFAULT NULLIF(current_setting('app.current_tenant', true), '')::uuid,
    aggregate_id uuid         NOT NULL,
    event_type   varchar(100) NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL,
    published_at timestamptz,
    attempts     integer      NOT NULL DEFAULT 0
);

CREATE INDEX ix_outbox_events_pending ON outbox_events (created_at) WHERE published_at IS NULL;

-- RLS forzado (ADR-005): sin app.current_tenant fijado no se ve ni se escribe ninguna fila.
ALTER TABLE matching_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE matching_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON matching_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE matching_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE matching_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON matching_attempts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- El servicio solo inserta eventos con el tenant de la sesión; los lee y marca como publicados únicamente el rol
-- del publicador (matching_outbox), que tiene BYPASSRLS (db/roles.sql).
ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_insert ON outbox_events FOR INSERT
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- El consumidor de service-request.created escribe aquí desde este despliegue; sin los permisos reintentaría hasta
-- que se vuelva a aplicar db/roles.sql. El dueño de las tablas (el migrador) los concede si el rol ya existe.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_app') THEN
        GRANT SELECT, INSERT, UPDATE ON matching_requests, matching_attempts TO matching_app;
        GRANT INSERT ON outbox_events TO matching_app;
    END IF;
END
$$;
