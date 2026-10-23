package org.springframework.samples.petclinic.customers.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("kafka")
public class VisitKafkaConfiguration {
    @Bean
    NewTopic visitCreatedTopic() {
        return TopicBuilder.name("petclinic.visit-created").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic visitCreatedDeadLetterTopic() {
        return TopicBuilder.name("petclinic.visit-created.DLT").partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler visitErrorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer,
            new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JsonProcessingException.class);
        return handler;
    }
}
