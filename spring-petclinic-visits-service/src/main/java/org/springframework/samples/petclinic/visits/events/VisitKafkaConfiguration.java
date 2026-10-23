package org.springframework.samples.petclinic.visits.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("kafka")
@EnableScheduling
public class VisitKafkaConfiguration {
    @Bean
    NewTopic visitCreatedTopic() {
        return TopicBuilder.name(VisitEventOutbox.TOPIC).partitions(3).replicas(1).build();
    }
}
