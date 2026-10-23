CREATE EXTENSION pg_stat_statements;
CREATE SCHEMA datadog AUTHORIZATION datadog;
GRANT USAGE ON SCHEMA public TO datadog;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO datadog;
-- Invoker permissions keep the monitoring function from gaining owner privileges.
CREATE FUNCTION datadog.explain_statement(l_query TEXT, OUT explain JSON)
RETURNS SETOF JSON AS $$
DECLARE
  curs REFCURSOR;
  plan JSON;
BEGIN
  SET TRANSACTION READ ONLY;
  OPEN curs FOR EXECUTE pg_catalog.concat('EXPLAIN (FORMAT JSON) ', l_query);
  FETCH curs INTO plan;
  CLOSE curs;
  RETURN QUERY SELECT plan;
END;
$$ LANGUAGE plpgsql RETURNS NULL ON NULL INPUT SECURITY INVOKER
SET search_path = pg_catalog, public;
REVOKE ALL ON FUNCTION datadog.explain_statement(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION datadog.explain_statement(TEXT) TO datadog;
