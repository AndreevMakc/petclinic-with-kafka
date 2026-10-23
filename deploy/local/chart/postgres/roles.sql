\getenv customers_password CUSTOMERS_PASSWORD
\getenv vets_password VETS_PASSWORD
\getenv visits_password VISITS_PASSWORD
\getenv monitor_password MONITOR_PASSWORD
CREATE ROLE customers LOGIN PASSWORD :'customers_password';
CREATE ROLE vets LOGIN PASSWORD :'vets_password';
CREATE ROLE visits LOGIN PASSWORD :'visits_password';
CREATE ROLE datadog LOGIN PASSWORD :'monitor_password';
GRANT pg_monitor TO datadog;
CREATE DATABASE customers OWNER customers;
CREATE DATABASE vets OWNER vets;
CREATE DATABASE visits OWNER visits;
