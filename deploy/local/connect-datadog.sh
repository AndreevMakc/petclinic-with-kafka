#!/usr/bin/env bash
set -euo pipefail
set +x
cd "$(dirname "$0")"

kubectl --context colima get node colima >/dev/null
if ! kubectl --context colima -n datadog get secret datadog-api-key >/dev/null 2>&1; then
  read -r -s -p 'API key for your EU Datadog account (hidden): ' task_datadog_api_key
  printf '\n'
  trap 'unset task_datadog_api_key' EXIT
  if [[ ! "$task_datadog_api_key" =~ ^[[:xdigit:]]{32}$ ]]; then
    printf 'Expected a 32-character Datadog API key.\n' >&2
    exit 1
  fi
  kubectl --context colima create namespace datadog --dry-run=client -o yaml |
    kubectl --context colima apply -f -
  printf '%s' "$task_datadog_api_key" |
    kubectl --context colima -n datadog create secret generic datadog-api-key \
      --from-file=api-key=/dev/stdin --dry-run=client -o yaml |
    kubectl --context colima -n datadog apply --server-side --field-manager=petclinic-local -f -
  unset task_datadog_api_key
fi

helm upgrade --install datadog datadog --repo https://helm.datadoghq.com \
  --version 3.260.0 --kube-context colima --namespace datadog \
  -f datadog-values.yaml --wait --timeout 8m
helm upgrade --install petclinic ./chart --kube-context colima \
  --namespace petclinic --create-namespace --reuse-values --set datadogEnabled=true \
  --wait --timeout 8m
printf '\nDatadog installed. Verify delivery in Agent status and in your account.\n'
printf 'Filter by env:local and kube_cluster_name:petclinic-colima.\n'
