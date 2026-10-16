package com.payflow.service;

import com.payflow.exception.PaymentProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MockBankGateway {

    private static final Logger log = LoggerFactory.getLogger(MockBankGateway.class);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, GatewayTransactionRecord> simulatedBankLedger = new ConcurrentHashMap<>();

    @Value("${app.mock-gateway.failure-rate-percent:0}")
    private int failureRatePercent;

    public record GatewayTransactionRecord(String authCode, String status, BigDecimal amount) {}

    /**
     * Simulates external bank clearing and card network authorization.
     */
    public String authorizeAndSettle(String paymentReference, BigDecimal amount, String currency) {
        log.info("Simulating external bank settlement for ref: {}, amount: {} {}", paymentReference, amount, currency);

        // Simulate network latency (20ms - 80ms)
        try {
            Thread.sleep(20 + random.nextInt(60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simulate probabilistic network failure or gateway reject for testing
        if (failureRatePercent > 0 && random.nextInt(100) < failureRatePercent) {
            log.warn("Mock bank gateway simulated transient timeout/failure for {}", paymentReference);
            throw new PaymentProcessingException("Bank gateway rejected transaction: BANK_TIMEOUT_REJECT");
        }

        String authCode = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        simulatedBankLedger.put(paymentReference, new GatewayTransactionRecord(authCode, "SETTLED", amount));
        return authCode;
    }

    /**
     * Query gateway status (used by reconciliation engine)
     */
    public GatewayTransactionRecord queryStatus(String paymentReference) {
        return simulatedBankLedger.getOrDefault(
            paymentReference,
            new GatewayTransactionRecord(null, "NOT_FOUND", BigDecimal.ZERO)
        );
    }

    public void setFailureRatePercent(int rate) {
        this.failureRatePercent = rate;
    }
}
