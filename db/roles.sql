-- Roles y permisos de Matching Service (DD, sección 10.2).
-- Lo ejecuta un administrador de la base DESPUÉS de las migraciones (que crean las tablas y las políticas RLS).
-- Las contraseñas no van aquí: se asignan aparte con ALTER ROLE ... LOGIN PASSWORD, desde el vault.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_app') THEN
        CREATE ROLE matching_app NOLOGIN NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_outbox') THEN
        CREATE ROLE matching_outbox NOLOGIN BYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_scheduler') THEN
        CREATE ROLE matching_scheduler NOLOGIN BYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO matching_app, matching_outbox, matching_scheduler;

-- Rol del servicio: todo bajo RLS. processed_events solo admite registrar y consultar.
GRANT SELECT, INSERT ON processed_events TO matching_app;

-- Proyección del técnico (DD 5.6, P-01): el consumidor de technician.* la crea y actualiza bajo RLS.
GRANT SELECT, INSERT, UPDATE ON technician_availability TO matching_app;

-- Cobertura del técnico (DD 5.7, P-04): catálogo de localidades de solo lectura; el servicio reemplaza las zonas.
GRANT SELECT ON localities TO matching_app;
GRANT SELECT, INSERT, DELETE ON coverage_zones TO matching_app;

-- Algoritmo de matching (DD 5.8 y 5.17): solicitudes y ofertas bajo RLS.
GRANT SELECT, INSERT, UPDATE ON matching_requests, matching_attempts TO matching_app;

-- Transactional Outbox (ADR-007, DD 5.15): el servicio inserta los eventos de su tenant (RLS); solo el publicador
-- los lee y marca como publicados, con SET LOCAL ROLE matching_outbox dentro de su transacción.
GRANT INSERT ON outbox_events TO matching_app;
GRANT SELECT, UPDATE ON outbox_events TO matching_outbox;
GRANT matching_outbox TO matching_app WITH INHERIT FALSE, SET TRUE;

-- Tareas programadas que recorren todos los tenants (P-06, DD 10.2): solo leen identificador, tenant, estado y
-- vencimiento; cada cambio se aplica después como matching_app con el tenant de la fila. Barrido de solicitudes en
-- espera (RN-M10) y vencimiento de ofertas (RN-M3, RN-M8).
GRANT SELECT (service_request_id, tenant_id, status, created_at) ON matching_requests TO matching_scheduler;
GRANT SELECT (id, tenant_id, response, expires_at) ON matching_attempts TO matching_scheduler;
GRANT matching_scheduler TO matching_app WITH INHERIT FALSE, SET TRUE;
