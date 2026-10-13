-- Roles y permisos de Matching Service (DD, sección 10.2).
-- Lo ejecuta un administrador de la base DESPUÉS de las migraciones (que crean las tablas y las políticas RLS).
-- Las contraseñas no van aquí: se asignan aparte con ALTER ROLE ... LOGIN PASSWORD, desde el vault.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'matching_app') THEN
        CREATE ROLE matching_app NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO matching_app;

-- Rol del servicio: todo bajo RLS. processed_events solo admite registrar y consultar.
GRANT SELECT, INSERT ON processed_events TO matching_app;

-- Proyección del técnico (DD 5.6, P-01): el consumidor de technician.* la crea y actualiza bajo RLS.
GRANT SELECT, INSERT, UPDATE ON technician_availability TO matching_app;

-- Cobertura del técnico (DD 5.7, P-04): catálogo de localidades de solo lectura; el servicio reemplaza las zonas.
GRANT SELECT ON localities TO matching_app;
GRANT SELECT, INSERT, DELETE ON coverage_zones TO matching_app;
