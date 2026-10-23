#!/usr/bin/env bash
set -euo pipefail
# Never print credentials, even when invoked as bash -x.
set +x
cd "$(dirname "$0")"
if [[ "${1:-}" == --help ]]; then
  printf 'Usage: bash up.sh\nRequires Colima, Docker CLI, kubectl, Helm, Python 3. Uses the colima context.\nStarts Petclinic + PostgreSQL + Kafka + Datadog (EU). Prompts securely for the API key on first run.\n'
  exit 0
fi
if [[ $# -ne 0 ]]; then printf 'Unknown option. Use --help.\n' >&2; exit 1; fi
for command in git colima docker kubectl helm python3; do
  command -v "$command" >/dev/null || { printf 'Install %s first; see README.md.\n' "$command" >&2; exit 1; }
done
git rev-parse --verify HEAD >/dev/null 2>&1 || {
  printf 'Use a git clone with at least one commit; the Maven build reads Git metadata.\n' >&2
  exit 1
}
if ! colima status >/dev/null 2>&1; then
  colima start --kubernetes --cpu 6 --memory 10
fi
kubectl --context colima wait --for=condition=Ready nodes --all --timeout=180s
docker --context colima info >/dev/null
for namespace in petclinic datadog; do
  kubectl --context colima create namespace "$namespace" --dry-run=client -o yaml |
    kubectl --context colima apply -f -
done
helm upgrade --install petclinic ./deploy/local/chart --kube-context colima \
  --namespace petclinic --reuse-values --wait --timeout 8m
bash deploy/local/connect-datadog.sh
bash deploy/local/enable-postgres.sh
bash deploy/local/enable-kafka.sh
printf '\nReady. In another terminal:\n  kubectl --context colima -n petclinic port-forward service/api-gateway 8080:8080\nThen open http://localhost:8080\n'
