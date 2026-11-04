-- technician_availability (DD 5.6): disponibilidad del técnico y proyección de su especialidad y verificación,
-- copiadas de los eventos technician.* de Identity (P-01, ADR-017).
CREATE TABLE technician_availability (
    technician_id         uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    specialty_id          uuid        NOT NULL,
    verification_status   varchar(25) NOT NULL,
    status                varchar(25) NOT NULL,
    updated_at            timestamptz NOT NULL,
    projection_updated_at timestamptz NOT NULL,
    CONSTRAINT ck_technician_availability_status
        CHECK (status IN ('disponible', 'ocupado', 'no_disponible')),
    CONSTRAINT ck_technician_availability_verification
        CHECK (verification_status IN ('pendiente', 'aprobado', 'rechazado', 'suspendido'))
);

-- Búsqueda de candidatos (DD 5.6, sección 10.2).
CREATE INDEX ix_technician_availability_candidates
    ON technician_availability (tenant_id, specialty_id, verification_status, status);

-- RLS forzado (ADR-005): sin app.current_tenant fijado no se ve ni se escribe ninguna fila.
ALTER TABLE technician_availability ENABLE ROW LEVEL SECURITY;
ALTER TABLE technician_availability FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON technician_availability
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- El consumidor escribe aquí desde este despliegue; sin el permiso reintentaría hasta que se vuelva a aplicar
-- db/roles.sql. El dueño de la tabla (el migrador) lo concede si el rol ya existe.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_app') THEN
        GRANT SELECT, INSERT, UPDATE ON technician_availability TO matching_app;
    END IF;
END
$$;
