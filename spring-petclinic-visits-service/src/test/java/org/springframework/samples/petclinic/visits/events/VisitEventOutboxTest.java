package org.springframework.samples.petclinic.visits.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitEventOutboxTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private VisitEventOutbox outbox;

    @BeforeEach
    void setUp() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        outbox = new VisitEventOutbox(jdbc, kafka, new ObjectMapper(), new SimpleMeterRegistry(), manager);
    }

    @Test
    void removesThePendingEventOnlyAfterKafkaAcknowledgesIt() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("event"))).thenReturn(List.of("payload"));
        when(kafka.send(VisitEventOutbox.TOPIC, "event", "payload"))
            .thenReturn(CompletableFuture.completedFuture(null));
        assertThat(outbox.publish("event")).isTrue();
        var order = inOrder(kafka, jdbc, manager);
        order.verify(kafka).send(VisitEventOutbox.TOPIC, "event", "payload");
        order.verify(jdbc).update("DELETE FROM visit_outbox WHERE event_id = ?", "event");
        order.verify(manager).commit(any());
    }

    @Test
    void keepsTheEventAndRollsBackWhenKafkaIsUnavailable() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("event"))).thenReturn(List.of("payload"));
        when(kafka.send(VisitEventOutbox.TOPIC, "event", "payload"))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
        assertThat(outbox.publish("event")).isFalse();
        verify(jdbc, never()).update(startsWith("DELETE"), any(Object.class));
        verify(manager).rollback(any());
    }

    @Test
    void skipsAnEventAlreadyLockedOrDeliveredByAnotherPublisher() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("event"))).thenReturn(List.of());
        assertThat(outbox.publish("event")).isTrue();
        verifyNoInteractions(kafka);
    }
}
