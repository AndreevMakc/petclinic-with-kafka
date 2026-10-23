package org.springframework.samples.petclinic.visits.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Profile("kafka")
public class VisitEventOutbox {
    public static final String TOPIC = "petclinic.visit-created";
    private static final Logger log = LoggerFactory.getLogger(VisitEventOutbox.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;

    public VisitEventOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                            MeterRegistry meters, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
        this.meters = meters;
        this.transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        meters.gauge("petclinic.kafka.outbox.pending", jdbc,
            db -> db.queryForObject("SELECT count(*) FROM visit_outbox", Long.class));
    }

    // Synchronous listener: the visit and its outbox entry commit in the same DB transaction.
    @EventListener
    public void store(VisitCreatedEvent event) {
        try {
            jdbc.update("INSERT INTO visit_outbox(event_id, payload) VALUES (?, ?)",
                event.eventId(), mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize visit event", e);
        }
    }

    @TransactionalEventListener
    public void afterCommit(VisitCreatedEvent event) {
        // Sending here preserves the HTTP trace context in the normal path.
        publish(event.eventId());
    }

    @Scheduled(fixedDelayString = "${petclinic.kafka.retry-delay-ms:5000}")
    public void retryPending() {
        // shortcut: background retries have a new trace; persist trace context if cross-retry lineage is required.
        for (String eventId : jdbc.queryForList(
                "SELECT event_id FROM visit_outbox ORDER BY created_at LIMIT 10", String.class)) {
            if (!publish(eventId)) {
                break;
            }
        }
    }

    public boolean publish(String eventId) {
        try {
            transactions.executeWithoutResult(status -> {
                var payloads = jdbc.queryForList(
                    "SELECT payload FROM visit_outbox WHERE event_id = ? FOR UPDATE SKIP LOCKED",
                    String.class, eventId);
                if (payloads.isEmpty()) {
                    return;
                }
                send(eventId, payloads.get(0));
                jdbc.update("DELETE FROM visit_outbox WHERE event_id = ?", eventId);
                meters.counter("petclinic.kafka.events.published").increment();
                log.info("Published visit event event_id={} topic={}", eventId, TOPIC);
            });
            return true;
        } catch (RuntimeException e) {
            meters.counter("petclinic.kafka.publish.failures").increment();
            log.warn("Visit event remains in outbox for retry event_id={}", eventId, e);
            return false;
        }
    }

    private void send(String eventId, String payload) {
        try {
            kafka.send(TOPIC, eventId, payload).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka send interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge visit event", e);
        }
    }
}
