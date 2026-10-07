package com.anitec.backend.shared.domain;

/**
 * Marker for in-process domain events. Events are published through
 * {@code shared.application.DomainEventDispatcher} and consumed synchronously
 * inside the same transaction (state propagation).
 */
public interface DomainEvent {
}
