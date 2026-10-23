package org.springframework.samples.petclinic.visits.events;

public record VisitCreatedEvent(String eventId, int visitId, int petId, String visitDate) { }
