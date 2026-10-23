#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
docker --context colima run --rm \
  -v "$PWD:/workspace" -v petclinic-maven-cache:/root/.m2 -w /workspace \
  maven:3.9.11-eclipse-temurin-17 mvn -B -ntp \
  -pl spring-petclinic-visits-service,spring-petclinic-customers-service -am clean verify
for service in customers-service visits-service; do
  docker --context colima build -f deploy/local/kafka/Dockerfile \
    --build-arg "SERVICE=$service" -t "petclinic-local/$service:3.4.1-kafka2" .
done
kubectl --context colima -n petclinic exec -i petclinic-postgres-0 -- \
  psql -U visits -d visits -v ON_ERROR_STOP=1 < deploy/local/kafka/visits-v3.sql
kubectl --context colima -n petclinic exec -i petclinic-postgres-0 -- \
  psql -U customers -d customers -v ON_ERROR_STOP=1 < deploy/local/kafka/customers-v2.sql
helm upgrade datadog datadog --repo https://helm.datadoghq.com \
  --version 3.260.0 --kube-context colima --namespace datadog \
  -f deploy/local/datadog-values.yaml -f deploy/local/datadog-kafka-values.yaml --wait --timeout 6m
helm upgrade petclinic ./deploy/local/chart --kube-context colima --namespace petclinic \
  --reuse-values --set kafka.enabled=true --set kafka.applicationsEnabled=true \
  --set kafka.image=apache/kafka:3.9.1 --set kafka.storage=2Gi \
  --set kafka.applicationImageTag=3.4.1-kafka2 --wait --timeout 8m
