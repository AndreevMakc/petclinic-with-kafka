#!/usr/bin/env bash
set -euo pipefail
psql --username postgres --dbname postgres --set ON_ERROR_STOP=1 --file /petclinic-init/roles.sql
for database in customers vets visits; do
  psql --username "$database" --dbname "$database" --set ON_ERROR_STOP=1 --file "/petclinic-init/$database-schema.sql"
  psql --username "$database" --dbname "$database" --set ON_ERROR_STOP=1 --file "/petclinic-init/$database-data.sql"
  if [ "$database" = visits ]; then
    psql --username visits --dbname visits --set ON_ERROR_STOP=1 --file /petclinic-init/visits-v2.sql
  fi
  psql --username postgres --dbname "$database" --set ON_ERROR_STOP=1 --file /petclinic-init/monitor.sql
done
