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
