package com.demo.cardsubscription.application.port.in;

/**
 * Inbound port: retry subscriptions that failed or were left PENDING by a crash.
 * Driven by the retry job.
 */
public interface RetrySubscriptionsUseCase {

    /** @return how many subscriptions were retried */
    int retryDue();
}
