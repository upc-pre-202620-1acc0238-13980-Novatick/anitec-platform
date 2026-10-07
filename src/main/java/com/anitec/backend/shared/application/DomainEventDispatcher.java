package com.anitec.backend.shared.application;

import com.anitec.backend.shared.domain.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process domain event dispatcher (spec section 11). State-propagating
 * consumers subscribe with {@code @EventListener} so they run synchronously in
 * the publisher's transaction (subscription -> capacity limit updates keep
 * their revision ordering).
 */
@Component
public class DomainEventDispatcher {

    private final ApplicationEventPublisher publisher;

    public DomainEventDispatcher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(DomainEvent... events) {
        for (DomainEvent event : events) {
            publisher.publishEvent(event);
        }
    }
}
