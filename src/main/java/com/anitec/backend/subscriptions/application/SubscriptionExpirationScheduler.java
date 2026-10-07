package com.anitec.backend.subscriptions.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for subscription expiry. Kept in its own component so the
 * call crosses the service proxy and runs inside a transaction.
 */
@Component
public class SubscriptionExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpirationScheduler.class);

    private final SubscriptionService subscriptionService;

    public SubscriptionExpirationScheduler(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /** Every 30 minutes: apply expiry/renewal for due premium subscriptions (TS02). */
    @Scheduled(cron = "0 */30 * * * *")
    public void expireDueSubscriptions() {
        try {
            int processed = subscriptionService.expireDueSubscriptions();
            if (processed > 0) {
                log.info("Expired {} premium subscriptions to their free plan", processed);
            }
        } catch (Exception ex) {
            log.error("Subscription expiry sweep failed", ex);
        }
    }
}
