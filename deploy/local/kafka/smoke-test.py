"""Exercise the live Kafka flow, duplicate delivery, and broker outage recovery."""
import json
import subprocess
import time
import uuid

KUBECTL = ["kubectl", "--context", "colima", "-n", "petclinic"]


def kube(*args, input=None):
    return subprocess.run(KUBECTL + list(args), input=input, text=True,
                          capture_output=True, check=True, timeout=180).stdout.strip()


def sql(database, query):
    return kube("exec", "petclinic-postgres-0", "--", "psql", "-U", database,
                "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query)


def http(path, body=None):
    args = ["exec", "deployment/api-gateway", "-c", "app", "--", "curl", "-fsS",
            "--max-time", "20"]
    if body is not None:
        args += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    return json.loads(kube(*args, "http://localhost:8080" + path))


def notification(visit_id):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        records = http("/api/customer/owners/6/visit-notifications")
        for record in records:
            if record["visit_id"] == visit_id:
                return record
        time.sleep(2)
    raise AssertionError(f"Visit {visit_id} was not delivered to customers")


def create_visit():
    visit = http("/api/visit/owners/6/pets/7/visits",
                 {"date": "2026-10-23", "description": "Kafka smoke " + str(uuid.uuid4())})
    return int(visit["id"])


visit_id = create_visit()
record = notification(visit_id)
event = {"eventId": record["event_id"], "visitId": visit_id,
         "petId": 7, "visitDate": "2026-10-23"}
kube("exec", "-i", "petclinic-kafka-0", "--",
     "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
     "--topic", "petclinic.visit-created", input=json.dumps(event) + "\n")
time.sleep(5)
assert sql("customers", f"SELECT count(*) FROM visit_notifications WHERE visit_id={visit_id}") == "1"
print("PASS: HTTP visit -> Kafka -> customer notification; duplicate event created no extra row", flush=True)

invalid_payload = "invalid-event-" + str(uuid.uuid4())
kube("exec", "-i", "petclinic-kafka-0", "--",
     "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
     "--topic", "petclinic.visit-created", input=invalid_payload + "\n")
dead_letter = kube("exec", "petclinic-kafka-0", "--",
                   "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                   "--topic", "petclinic.visit-created.DLT", "--from-beginning",
                   "--timeout-ms", "10000")
assert invalid_payload in dead_letter.splitlines(), "Invalid payload must reach the configured .DLT topic"
print("PASS: malformed event delivered to dead-letter topic", flush=True)

try:
    kube("scale", "statefulset", "petclinic-kafka", "--replicas=0")
    kube("wait", "--for=delete", "pod/petclinic-kafka-0", "--timeout=90s")
    pending_visit = create_visit()
    pending = sql("visits", "SELECT count(*) FROM visit_outbox "
                  f"WHERE (payload::jsonb->>'visitId')::integer={pending_visit}")
    assert pending == "1", "A visit created during a Kafka outage must have a durable pending event"
    print("PASS: visit creation succeeded during Kafka outage; event remains in PostgreSQL outbox", flush=True)
finally:
    kube("scale", "statefulset", "petclinic-kafka", "--replicas=1")
    kube("rollout", "status", "statefulset", "petclinic-kafka", "--timeout=120s")

notification(pending_visit)
assert sql("visits", "SELECT count(*) FROM visit_outbox "
           f"WHERE (payload::jsonb->>'visitId')::integer={pending_visit}") == "0"
print("PASS: Kafka recovered; pending event delivered and removed from outbox", flush=True)
