DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_extension WHERE extname = 'pg_stat_statements') THEN
    RAISE EXCEPTION 'pg_stat_statements is missing';
  END IF;
  IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('customers', 'vets', 'visits', 'datadog') AND rolsuper) THEN
    RAISE EXCEPTION 'Application and monitoring users must not be superusers';
  END IF;
  IF NOT has_function_privilege('datadog', 'datadog.explain_statement(text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Datadog EXPLAIN permission is missing';
  END IF;
  IF current_database() = 'visits' AND NOT EXISTS (
    SELECT FROM information_schema.columns WHERE table_schema = 'public'
      AND table_name = 'visits' AND column_name = 'visit_date'
      AND data_type = 'timestamp without time zone'
  ) THEN
    RAISE EXCEPTION 'Visit.date requires a timestamp column';
  END IF;
END $$;
