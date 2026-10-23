package org.springframework.samples.petclinic.visits.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.samples.petclinic.visits.events.VisitCreatedEvent;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VisitCreationTest {
    private final VisitRepository repository = mock(VisitRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final VisitResource resource = new VisitResource(repository, events);

    @Test
    void createsAnEventWithThePersistedVisitIdAndThePathPetId() {
        Visit visit = new Visit();
        visit.setPetId(999);
        when(repository.save(visit)).thenAnswer(invocation -> {
            visit.setId(42);
            return visit;
        });

        assertThat(resource.create(visit, 7)).isSameAs(visit);

        var event = org.mockito.ArgumentCaptor.forClass(VisitCreatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().visitId()).isEqualTo(42);
        assertThat(event.getValue().petId()).isEqualTo(7);
        assertThat(event.getValue().eventId()).isNotBlank();
    }

    @Test
    void doesNotPublishWhenSavingTheVisitFails() {
        when(repository.save(any())).thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(() -> resource.create(new Visit(), 7))
            .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(events);
    }
}
