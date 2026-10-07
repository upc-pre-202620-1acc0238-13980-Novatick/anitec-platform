package com.anitec.backend.admin.application;

import com.anitec.backend.admin.domain.AuditEntry;
import com.anitec.backend.admin.domain.AuditRepository;
import com.anitec.backend.identity.application.IdentityQueries;
import com.anitec.backend.identity.application.IdentityService;
import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.livestock.application.LivestockService;
import com.anitec.backend.livestock.domain.Species;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.application.SubscriptionService;
import com.anitec.backend.subscriptions.domain.Plan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin application service (decision #16: users + species + plans + audit).
 * Every mutation delegates to the owning context's public application API
 * (context map section 5.2) and records one audit entry.
 */
@Service
public class AdminService {

    private final IdentityQueries identityQueries;
    private final IdentityService identityService;
    private final LivestockService livestockService;
    private final SubscriptionService subscriptionService;
    private final AuditRepository audits;

    public AdminService(IdentityQueries identityQueries, IdentityService identityService,
                        LivestockService livestockService, SubscriptionService subscriptionService,
                        AuditRepository audits) {
        this.identityQueries = identityQueries;
        this.identityService = identityService;
        this.livestockService = livestockService;
        this.subscriptionService = subscriptionService;
        this.audits = audits;
    }

    public record AuditView(UUID id, UUID actorId, String action, String entityType, UUID entityId,
                            String details, java.time.Instant createdAt) {
        static AuditView from(AuditEntry entry) {
            return new AuditView(entry.getId(), entry.getActorId(), entry.getAction(),
                    entry.getEntityType(), entry.getEntityId(), entry.getDetails(), entry.getCreatedAt());
        }
    }

    // --------------------------------------------------------------- users

    public IdentityQueries.AccountPage searchUsers(String query, Role role, int page, int size) {
        return identityQueries.searchAccounts(query, role, page, size);
    }

    @Transactional
    public Account changeUserStatus(UUID actorId, UUID userId, Account.Status status) {
        if (status == Account.Status.PENDIENTE) {
            throw DomainException.badRequest("VALIDATION_ERROR",
                    "status: solo ACTIVA o SUSPENDIDA pueden aplicarse por admin");
        }
        Account account = identityService.changeUserStatus(userId, status);
        audit(actorId, "USER_STATUS_CHANGED", "Account", userId, "status=" + status.name());
        return account;
    }

    // ------------------------------------------------------------- species

    @Transactional
    public Species createSpecies(UUID actorId, String name, String description, String photoUrl) {
        Species species = livestockService.createSpecies(name, description, photoUrl);
        audit(actorId, "SPECIES_CREATED", "Species", species.getId(), "name=" + species.getName());
        return species;
    }

    @Transactional
    public Species updateSpecies(UUID actorId, UUID speciesId, String name, String description,
                                 String photoUrl) {
        Species species = livestockService.updateSpecies(speciesId, name, description, photoUrl);
        audit(actorId, "SPECIES_UPDATED", "Species", speciesId, "name=" + species.getName());
        return species;
    }

    @Transactional
    public void deleteSpecies(UUID actorId, UUID speciesId) {
        livestockService.deleteSpecies(speciesId);
        audit(actorId, "SPECIES_DELETED", "Species", speciesId, null);
    }

    // --------------------------------------------------------------- plans

    @Transactional
    public SubscriptionService.PlanView createPlan(UUID actorId, String name, Role profile,
                                                   Plan.PlanType type, BigDecimal price,
                                                   String currency, String billingPeriod,
                                                   int capacityLimit, boolean active) {
        SubscriptionService.PlanView plan = subscriptionService.createPlan(name, profile, type, price,
                currency, billingPeriod, capacityLimit, active);
        audit(actorId, "PLAN_CREATED", "Plan", plan.id(), "capacity=" + capacityLimit);
        return plan;
    }

    @Transactional
    public SubscriptionService.PlanView updatePlan(UUID actorId, UUID planId, String name,
                                                   BigDecimal price, Integer capacityLimit,
                                                   Boolean active) {
        SubscriptionService.PlanView plan = subscriptionService.updatePlan(planId, name, price,
                capacityLimit, active);
        audit(actorId, "PLAN_UPDATED", "Plan", planId, "capacity=" + plan.capacityLimit());
        return plan;
    }

    public List<SubscriptionService.PlanView> allPlans() {
        return subscriptionService.allPlans();
    }

    // --------------------------------------------------------------- audit

    public record AuditPage(List<AuditView> content, int page, int size, long total) {
    }

    public AuditPage auditLog(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<AuditView> content = audits.findRecent(safePage * safeSize, safeSize).stream()
                .map(AuditView::from).toList();
        return new AuditPage(content, safePage, safeSize, audits.count());
    }

    private void audit(UUID actorId, String action, String entityType, UUID entityId, String details) {
        audits.save(AuditEntry.create(actorId, action, entityType, entityId, details));
    }
}
