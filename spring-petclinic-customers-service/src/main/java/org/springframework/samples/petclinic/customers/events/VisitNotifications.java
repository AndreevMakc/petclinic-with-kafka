package org.springframework.samples.petclinic.customers.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("kafka")
public class VisitNotifications {
    private static final Logger log = LoggerFactory.getLogger(VisitNotifications.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public VisitNotifications(JdbcTemplate jdbc, ObjectMapper mapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.meters = meters;
    }

    @KafkaListener(topics = "petclinic.visit-created", groupId = "petclinic-customers")
    @Transactional
    public void consume(String payload) throws JsonProcessingException {
        var event = mapper.readValue(payload, VisitCreated.class);
        if (event == null) {
            throw new IllegalArgumentException("Visit event must be an object");
        }
        int inserted = jdbc.update("""
            INSERT INTO visit_notifications(event_id, visit_id, pet_id, visit_date)
            VALUES (?, ?, ?, CAST(? AS DATE)) ON CONFLICT (event_id) DO NOTHING
            """, event.eventId(), event.visitId(), event.petId(), event.visitDate());
        meters.counter("petclinic.kafka.events.consumed", "result", inserted == 1 ? "created" : "duplicate").increment();
        log.info("Consumed visit event event_id={} visit_id={} pet_id={} inserted={}",
            event.eventId(), event.visitId(), event.petId(), inserted);
    }

    @GetMapping("/owners/{ownerId}/visit-notifications")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forOwner(@PathVariable int ownerId) {
        return jdbc.queryForList("""
            SELECT n.event_id, n.visit_id, n.pet_id, n.visit_date, n.received_at
            FROM visit_notifications n JOIN pets p ON p.id = n.pet_id
            WHERE p.owner_id = ? ORDER BY n.received_at DESC LIMIT 100
            """, ownerId);
    }

    public record VisitCreated(String eventId, int visitId, int petId, String visitDate) {
        public VisitCreated {
            if (eventId == null || visitDate == null || visitId < 1 || petId < 1) {
                throw new IllegalArgumentException("A visit event requires an event ID, date and positive visit/pet IDs");
            }
            UUID.fromString(eventId);
            LocalDate.parse(visitDate);
        }
    }
}
