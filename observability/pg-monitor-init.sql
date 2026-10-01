-- Login role for postgres-exporter: read-only statistics through the built-in
-- pg_monitor role, nothing else. Re-running it only resets the password.
-- The password comes from the compose secret file, so it never appears on argv.
\set ON_ERROR_STOP on
\set monitor_password `cat /run/secrets/postgres_exporter_password`
-- \g /dev/null: the result row is the password itself and must not reach the logs.
SELECT set_config('flowcortex.monitor_password', :'monitor_password', false) \g /dev/null

DO $$
BEGIN
  IF length(current_setting('flowcortex.monitor_password')) < 8 THEN
    RAISE EXCEPTION 'POSTGRES_EXPORTER_PASSWORD must have at least 8 characters';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'flowcortex_monitor') THEN
    CREATE ROLE flowcortex_monitor LOGIN;
  END IF;
  EXECUTE format('ALTER ROLE flowcortex_monitor WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',
                 current_setting('flowcortex.monitor_password'));
  GRANT pg_monitor TO flowcortex_monitor;
END
$$;
