# Petclinic with Kafka, PostgreSQL and Datadog

A local Kubernetes lab based on [Spring Petclinic Microservices v3.4.1](https://github.com/spring-petclinic/spring-petclinic-microservices/tree/v3.4.1), extended with a real Kafka producer/consumer flow.

```text
Browser / load generator → API Gateway → Visits service → PostgreSQL (visit + outbox)
                                                ↓
                                   Kafka: petclinic.visit-created
                                                ↓
                                     Customers service → PostgreSQL (notification)
```

## One-command start

Tested platform: macOS Apple Silicon, Colima with Docker runtime and Kubernetes. Allocate at least 6 CPUs / 10 GiB to Colima, with additional free disk space for images and Maven dependencies. The script uses the dedicated `colima` Kubernetes and Docker contexts; it does not deploy to your current Kubernetes context. Windows, Docker Desktop, kind, and multi-node clusters are not supported by this launcher.

Install Colima, Docker CLI, kubectl, Helm and Python 3 first. For example, with Homebrew:

```sh
brew install colima docker kubernetes-cli helm python
```

Use a Datadog account on **EU1 (`datadoghq.eu`)** and your own API key. An application key is not needed.

```sh
git clone https://github.com/AndreevMakc/petclinic-with-kafka.git
cd petclinic-with-kafka
bash up.sh
```

The command starts Colima if stopped, deploys Petclinic, installs Datadog, builds the modified services, enables PostgreSQL/Kafka, and starts continuous traffic. The first run downloads images and Maven dependencies and can take several minutes. Java/Maven are run inside Docker; no local JDK is required. If Colima is already running, Kubernetes must already be enabled (`colima start --kubernetes`).

The API key is requested with hidden input on the first run. It goes through stdin into `datadog/datadog-api-key`, never into a repository file or command argument. Re-runs reuse that Secret. PostgreSQL passwords are generated locally and stored in Kubernetes Secrets. Do not export Secrets or commit kubeconfig, logs, `.env` files, or cluster dumps. Kubernetes Secrets are base64-encoded, not encrypted by default; anyone with sufficient local cluster access can read them.

After deployment, expose the application in a second terminal:

```sh
kubectl --context colima -n petclinic port-forward service/api-gateway 8080:8080
```

Open [Petclinic](http://localhost:8080). To inspect notifications generated through Kafka:

```sh
curl http://localhost:8080/api/customer/owners/6/visit-notifications
```

The load generator creates a visit approximately every 10 seconds, plus regular read traffic. Re-running `up.sh` retains database and Kafka PVCs, but temporarily rolls application profiles through the PostgreSQL setup stage; requests can fail during the update.

## What to look for in Datadog

Use `env:local`, cluster `petclinic-colima`, services `petclinic-*`.

- Java runtime and Micrometer metrics; application logs with trace IDs.
- HTTP, JDBC and Kafka producer/consumer spans.
- Kafka topic `petclinic.visit-created`, JMX broker metrics and consumer lag.
- PostgreSQL metrics and Database Monitoring with query correlation.
- Data Streams Monitoring is enabled in the two Kafka applications.

This lab enables broad metrics collection and DBM/DSM features. Usage can count toward your Datadog plan or trial allowances.

## Delivery and verification

Visits and outbox entries commit together. The publisher removes an entry only after Kafka acknowledges it; failures retry every 5 seconds. Delivery is at least once; the consumer deduplicates by event ID. Invalid messages go to `petclinic.visit-created.DLT`.

```sh
# Creates test visits and briefly stops/restarts Kafka; restores the broker in finally.
python3 deploy/local/kafka/smoke-test.py
```

The live smoke test covers delivery, duplicate handling, invalid messages and recovery after a broker outage. Java tests run during `up.sh`. Detailed versions, individual setup steps and troubleshooting: [local deployment guide](deploy/local/README.md).

## Stop and limitations

`colima stop` pauses the local lab and telemetry. It stops all workloads in that Colima instance. Data remains on local PVCs; deleting Colima or the PVCs deletes it.

This is a learning lab: one Kafka broker and one PostgreSQL instance, no replication, backups, authentication/TLS on Kafka, or production hardening. Background outbox retries start a new trace; the event ID provides correlation to the original request. Notifications are database records exposed through an API; no email is sent.

## Source and license

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Original upstream documentation is preserved in [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md). Local changes add Kafka messaging, the outbox/consumer, Kubernetes deployment and Datadog instrumentation. Upstream UI assets and synthetic sample data are retained. Built images and runtime data are not included.

This repository is an experimental snapshot, maintained by [AndreevMakc](https://github.com/AndreevMakc), without a release or security-update SLA. For sensitive security reports, use GitHub's private vulnerability reporting if available; never put credentials in a public issue. Review dependencies before reuse beyond a disposable local lab.
