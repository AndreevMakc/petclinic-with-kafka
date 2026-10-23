#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
import base64
import json
import secrets
import subprocess

kubectl = ['kubectl', '--context', 'colima']
result = subprocess.run(kubectl + ['-n', 'petclinic', 'get', 'secret', 'petclinic-postgres', '--ignore-not-found', '-o', 'json'], check=True, capture_output=True, text=True)
if result.stdout.strip():
    data = json.loads(result.stdout)['data']
else:
    data = {name + '-password': base64.b64encode(secrets.token_urlsafe(32).encode()).decode()
            for name in ['admin', 'customers', 'vets', 'visits', 'monitor']}
    secret = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'petclinic-postgres', 'namespace': 'petclinic'}, 'type': 'Opaque', 'data': data}
    subprocess.run(kubectl + ['apply', '--server-side', '--field-manager=petclinic-local', '-f', '-'], input=json.dumps(secret), text=True, check=True)
monitor = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'petclinic-postgres-monitor', 'namespace': 'datadog'}, 'type': 'Opaque', 'data': {'password': data['monitor-password']}}
subprocess.run(kubectl + ['apply', '--server-side', '--field-manager=petclinic-local', '-f', '-'], input=json.dumps(monitor), text=True, check=True)
PY
for service in customers-service vets-service visits-service; do
  docker --context colima build --build-arg "SERVICE_IMAGE=springcommunity/spring-petclinic-$service:3.4.1" \
    --tag "petclinic-local/$service:3.4.1-postgres1" ./postgres
done
if kubectl --context colima -n petclinic get statefulset petclinic-postgres >/dev/null 2>&1; then
  kubectl --context colima -n petclinic exec -i petclinic-postgres-0 -- \
    psql -U visits -d visits -v ON_ERROR_STOP=1 < chart/postgres/visits-v2.sql
fi
helm upgrade datadog datadog --repo https://helm.datadoghq.com \
  --version 3.260.0 --kube-context colima --namespace datadog \
  -f datadog-values.yaml --wait --timeout 5m
helm upgrade petclinic ./chart --kube-context colima --namespace petclinic \
  --reset-values --set datadogEnabled=true --set loadGenerator.enabled=true \
  --set postgresql.enabled=true --set postgresql.applicationImageTag=3.4.1-postgres1 \
  --set postgresql.image=postgres:17.8 --set postgresql.storage=2Gi --wait --timeout 8m
for database in customers vets visits; do
  kubectl --context colima -n petclinic exec -i petclinic-postgres-0 -- \
    psql -U postgres -d "$database" -v ON_ERROR_STOP=1 < postgres/verify.sql
done
