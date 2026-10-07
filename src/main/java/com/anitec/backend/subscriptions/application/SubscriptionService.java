package com.anitec.backend.subscriptions.application;

import com.anitec.backend.identity.domain.Account;
import com.anitec.backend.shared.application.DomainEventDispatcher;
import com.anitec.backend.shared.domain.DomainException;
import com.anitec.backend.shared.domain.Role;
import com.anitec.backend.subscriptions.domain.Payment;
import com.anitec.backend.subscriptions.domain.PaymentGateway;
import com.anitec.backend.subscriptions.domain.PaymentRepository;
import com.anitec.backend.subscriptions.domain.Plan;
import com.anitec.backend.subscriptions.domain.PlanRepository;
import com.anitec.backend.subscriptions.domain.Subscription;
import com.anitec.backend.subscriptions.domain.SubscriptionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Subscriptions service (report class SubscriptionService): plan catalogue,
 * checkout through the PaymentGateway port, idempotent payment confirmation
 * (TS02), renewal cancellation, expiry and admin plan management. Confirmed
 * limit changes are published as PremiumActivated/PremiumExpired events so
 * Livestock and Linking update their capacities with increasing revisions
 * (decision #12).
 */
@Service
public class SubscriptionService {

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final PaymentRepository payments;
    private final PaymentGateway paymentGateway;
    private final DomainEventDispatcher events;

    public SubscriptionService(PlanRepository plans, SubscriptionRepository subscriptions,
                               PaymentRepository payments, PaymentGateway paymentGateway,
                               DomainEventDispatcher events) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.paymentGateway = paymentGateway;
        this.events = events;
    }

    // --------------------------------------------------------------- records

    public record PlanView(UUID id, String name, Role profile, String type, BigDecimal price,
                           String currency, String billingPeriod, int capacityLimit, boolean active) {
        static PlanView from(Plan plan) {
            return new PlanView(plan.getId(), plan.getName(), plan.getProfile(), plan.getType().name(),
                    plan.getPrice(), plan.getCurrency(), plan.getBillingPeriod(),
                    plan.getCapacityLimit(), plan.isActive());
        }
    }

    public record SubscriptionView(UUID subscriptionId, UUID planId, String planName, String planType,
                                   String status, int allowedCapacity, boolean renewalEnabled,
                                   Instant endsAt, long revision, BigDecimal price, String currency,
                                   String billingPeriod) {
    }

    public record CheckoutResult(String checkoutId, String status, String planName,
                                 BigDecimal amount, String currency) {
    }

    public enum PaymentOutcome {APPROVED, DECLINED}

    public record PaymentResultView(String checkoutId, String paymentStatus, UUID subscriptionId,
                                    String planType, int allowedCapacity, long revision, Instant endsAt) {
    }

    // -------------------------------------------------- free plan at signup

    /** AccountRegistered -> create the free subscription + initial capacity revision. */
    @EventListener
    @Transactional
    public void onAccountRegistered(Account.AccountRegistered event) {
        if (event.role() == Role.ADMIN) {
            return;
        }
        if (subscriptions.existsByAccountId(event.accountId())) {
            return;
        }
        Plan freePlan = plans.findFreeByProfile(event.role())
                .orElseThrow(() -> DomainException.internal(
                        "No existe el plan gratuito para el perfil " + event.role()));
        Subscription subscription = Subscription.createForAccount(
                event.accountId(), event.role(), freePlan, Instant.now());
        subscriptions.save(subscription);
        events.publish(new Subscription.SubscriptionLimitChanged(event.accountId(), event.role(),
                freePlan.getCapacityLimit(), subscription.getRevision()));
    }

    // -------------------------------------------------------------- commands

    /** US23: start a premium checkout (profile validated BEFORE any payment). */
    @Transactional
    public CheckoutResult requestPremium(UUID accountId, UUID planId) {
        Subscription subscription = requireSubscription(accountId);
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> DomainException.notFound("Plan no encontrado"));
        if (!plan.supports(subscription.getProfile())) {
            throw DomainException.business("PLAN_PROFILE_MISMATCH",
                    "El plan indicado no corresponde a su perfil");
        }
        if (!plan.isPremium()) {
            throw DomainException.badRequest("VALIDATION_ERROR", "planId: indique un plan premium");
        }
        if (subscription.isPremium() && subscription.getPlanId().equals(planId)) {
            throw DomainException.conflict("INVALID_STATE_TRANSITION",
                    "Ya tiene activo este plan premium");
        }
        String checkoutRef = paymentGateway.createCheckout(accountId, plan);
        Payment payment = Payment.pending(subscription.getId(), accountId, plan.getId(), checkoutRef,
                plan.getPrice(), plan.getCurrency(), Instant.now());
        payments.save(payment);
        return new CheckoutResult(checkoutRef, payment.getStatus().name(), plan.getName(),
                plan.getPrice(), plan.getCurrency());
    }

    /** TS02: idempotent confirmation - replays never duplicate effects. */
    @Transactional
    public PaymentResultView confirmPayment(UUID accountId, String checkoutId, PaymentOutcome outcome) {
        Payment payment = payments.findByGatewayRef(checkoutId)
                .orElseThrow(() -> DomainException.notFound("Pago no encontrado"));
        if (!payment.belongsTo(accountId)) {
            throw DomainException.forbidden("El pago no pertenece a su cuenta");
        }
        Subscription subscription = requireSubscription(payment.getAccountId());

        if (payment.getStatus() != Payment.Status.PENDING) {
            // Already processed: report current state without applying effects again.
            return toPaymentResult(payment, subscription);
        }

        Instant now = Instant.now();
        if (outcome == PaymentOutcome.DECLINED) {
            payment.applyResult(Payment.Status.DECLINED, now);
            payments.save(payment);
            return toPaymentResult(payment, subscription);
        }

        Plan plan = plans.findById(payment.getPlanId())
                .orElseThrow(() -> DomainException.notFound("Plan no encontrado"));
        payment.applyResult(Payment.Status.APPROVED, now);
        subscription.activatePremium(plan, now);
        payments.save(payment);
        subscriptions.save(subscription);
        events.publish(new Subscription.PremiumActivated(subscription.getAccountId(),
                subscription.getProfile(), plan.getCapacityLimit(), subscription.getRevision()));
        return toPaymentResult(payment, subscription);
    }

    /** US24: cancel renewal; premium is kept until endsAt (idempotent). */
    @Transactional
    public SubscriptionView cancelRenewal(UUID accountId) {
        Subscription subscription = requireSubscription(accountId);
        if (subscription.getPlanType() != Plan.PlanType.PREMIUM) {
            throw DomainException.business("INVALID_STATE_TRANSITION",
                    "No tiene una suscripción premium activa");
        }
        if (subscription.cancelRenewal()) {
            subscriptions.save(subscription);
            events.publish(new Subscription.RenewalCancelled(accountId));
        }
        return toView(subscription);
    }

    /** Expiry sweep: paid period over -> free limit (existing records are kept). */
    @Transactional
    public int expireDueSubscriptions() {
        Instant now = Instant.now();
        int processed = 0;
        for (Subscription subscription : subscriptions.findDue(now)) {
            if (subscription.getPlanType() != Plan.PlanType.PREMIUM
                    || subscription.getStatus() != Subscription.Status.ACTIVA) {
                continue;
            }
            if (subscription.isRenewalEnabled()) {
                // Fake gateway: renewal succeeded, extend the paid period.
                subscription.extendPeriod(now);
                subscriptions.save(subscription);
                continue;
            }
            Plan freePlan = plans.findFreeByProfile(subscription.getProfile())
                    .orElseThrow(() -> DomainException.internal(
                            "No existe el plan gratuito para el perfil " + subscription.getProfile()));
            subscription.expire(freePlan, now);
            subscriptions.save(subscription);
            events.publish(new Subscription.PremiumExpired(subscription.getAccountId(),
                    subscription.getProfile(), freePlan.getCapacityLimit(), subscription.getRevision()));
            processed++;
        }
        return processed;
    }

    // --------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<PlanView> plansFor(Role profile) {
        if (profile != Role.GANADERO && profile != Role.VETERINARIO) {
            throw DomainException.forbidden("Solo los perfiles ganadero y veterinario consultan planes");
        }
        return plans.findByProfile(profile).stream().filter(Plan::isActive)
                .map(PlanView::from).toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionView mySubscription(UUID accountId) {
        return toView(requireSubscription(accountId));
    }

    // ---------------------------------------------------- admin plan management

    @Transactional
    public PlanView createPlan(String name, Role profile, Plan.PlanType type, BigDecimal price,
                               String currency, String billingPeriod, int capacityLimit, boolean active) {
        Plan plan = Plan.create(name, profile, type, price, currency, billingPeriod, capacityLimit, active);
        return PlanView.from(plans.save(plan));
    }

    /** A capacity change bumps the revision of every active subscription of the plan. */
    @Transactional
    public PlanView updatePlan(UUID planId, String name, BigDecimal price, Integer capacityLimit,
                               Boolean active) {
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> DomainException.notFound("Plan no encontrado"));
        boolean limitChanged = capacityLimit != null && capacityLimit != plan.getCapacityLimit();
        plan.update(name, price, capacityLimit, active);
        plans.save(plan);

        if (limitChanged) {
            Instant now = Instant.now();
            for (Subscription subscription : subscriptions.findByPlanId(planId)) {
                if (subscription.getStatus() != Subscription.Status.ACTIVA) {
                    continue;
                }
                subscription.applyCapacity(capacityLimit, now);
                subscriptions.save(subscription);
                events.publish(new Subscription.SubscriptionLimitChanged(subscription.getAccountId(),
                        subscription.getProfile(), capacityLimit, subscription.getRevision()));
            }
        }
        return PlanView.from(plan);
    }

    @Transactional(readOnly = true)
    public List<PlanView> allPlans() {
        return plans.findAll().stream().map(PlanView::from).toList();
    }

    // -------------------------------------------------------------- helpers

    private Subscription requireSubscription(UUID accountId) {
        return subscriptions.findByAccountId(accountId)
                .orElseThrow(() -> DomainException.notFound("Suscripción no encontrada"));
    }

    private SubscriptionView toView(Subscription subscription) {
        Plan plan = plans.findById(subscription.getPlanId())
                .orElseThrow(() -> DomainException.internal("Plan de la suscripción no encontrado"));
        return new SubscriptionView(subscription.getId(), subscription.getPlanId(), plan.getName(),
                subscription.getPlanType().name(), subscription.getStatus().name(),
                subscription.getAllowedCapacity(), subscription.isRenewalEnabled(),
                subscription.getEndsAt(), subscription.getRevision(), plan.getPrice(),
                plan.getCurrency(), plan.getBillingPeriod());
    }

    private PaymentResultView toPaymentResult(Payment payment, Subscription subscription) {
        return new PaymentResultView(payment.getGatewayRef(), payment.getStatus().name(),
                subscription.getId(), subscription.getPlanType().name(),
                subscription.getAllowedCapacity(), subscription.getRevision(), subscription.getEndsAt());
    }
}
