-- processed_events (DD 5.16): eventos ya aplicados por los consumidores de Matching (ADR-007, RN-EV1).
CREATE TABLE processed_events (
    event_id     uuid         PRIMARY KEY,
    tenant_id    uuid         NOT NULL,
    event_type   varchar(100) NOT NULL,
    processed_at timestamptz  NOT NULL
);

-- RLS forzado (ADR-005): sin app.current_tenant fijado no se ve ni se escribe ninguna fila.
ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON processed_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
