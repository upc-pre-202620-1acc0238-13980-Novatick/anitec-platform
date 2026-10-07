package com.anitec.backend.subscriptions.domain;

import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Plan entity (Subscriptions context): one free and one premium plan per
 * profile (decision #15: farmer 15/150 animals, vet 3/25 links).
 */
@Getter
public class Plan {

    public enum PlanType {GRATUITO, PREMIUM}

    private UUID id;
    private String name;
    private Role profile;
    private PlanType type;
    private BigDecimal price;
    private String currency;
    private String billingPeriod;
    private int capacityLimit;
    private boolean active;

    protected Plan() {
    }

    private Plan(UUID id, String name, Role profile, PlanType type, BigDecimal price, String currency,
                 String billingPeriod, int capacityLimit, boolean active) {
        this.id = id;
        this.name = name;
        this.profile = profile;
        this.type = type;
        this.price = price;
        this.currency = currency;
        this.billingPeriod = billingPeriod;
        this.capacityLimit = capacityLimit;
        this.active = active;
    }

    public static Plan create(String name, Role profile, PlanType type, BigDecimal price,
                              String currency, String billingPeriod, int capacityLimit, boolean active) {
        if (profile != Role.GANADERO && profile != Role.VETERINARIO) {
            throw DomainException.badRequest("VALIDATION_ERROR", "profile: solo GANADERO o VETERINARIO");
        }
        if (capacityLimit < 0) {
            throw DomainException.badRequest("VALIDATION_ERROR", "capacityLimit: no puede ser negativo");
        }
        return new Plan(UUID.randomUUID(), name, profile, type, price, currency, billingPeriod,
                capacityLimit, active);
    }

    public static Plan restore(UUID id, String name, Role profile, PlanType type, BigDecimal price,
                               String currency, String billingPeriod, int capacityLimit, boolean active) {
        return new Plan(id, name, profile, type, price, currency, billingPeriod, capacityLimit, active);
    }

    public void update(String name, BigDecimal price, Integer capacityLimit, Boolean active) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (price != null) {
            this.price = price;
        }
        if (capacityLimit != null) {
            if (capacityLimit < 0) {
                throw DomainException.badRequest("VALIDATION_ERROR", "capacityLimit: no puede ser negativo");
            }
            this.capacityLimit = capacityLimit;
        }
        if (active != null) {
            this.active = active;
        }
    }

    public boolean supports(Role requestedProfile) {
        return profile == requestedProfile;
    }

    public boolean isPremium() {
        return type == PlanType.PREMIUM;
    }
}
