package org.springframework.samples.petclinic.customers.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitNotificationsTest {
    private static final String PAYLOAD = """
        {"eventId":"3cef6694-e2e5-47ae-9819-b38438a0cf8d","visitId":42,"petId":7,"visitDate":"2026-10-23"}
        """;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final VisitNotifications notifications = new VisitNotifications(jdbc, new ObjectMapper(), meters);

    @Test
    void countsRedeliveryAsDuplicateInsteadOfCreatingAnotherNotification() throws Exception {
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1, 0);
        notifications.consume(PAYLOAD);
        notifications.consume(PAYLOAD);
        assertThat(meters.counter("petclinic.kafka.events.consumed", "result", "created").count()).isEqualTo(1);
        assertThat(meters.counter("petclinic.kafka.events.consumed", "result", "duplicate").count()).isEqualTo(1);
    }

    @Test
    void databaseFailurePropagatesToKafkaSoTheRecordCanBeRetried() {
        when(jdbc.update(anyString(), any(), any(), any(), any()))
            .thenThrow(new DataAccessResourceFailureException("Database unavailable"));
        assertThatThrownBy(() -> notifications.consume(PAYLOAD))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void rejectsNullMessageAsInvalidInputBeforeAccessingTheDatabase() {
        assertThatThrownBy(() -> notifications.consume("null"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> notifications.consume("{broken"))
            .isInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(jdbc);
    }
}
