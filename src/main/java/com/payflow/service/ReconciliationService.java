package com.payflow.service;

import com.payflow.entity.Payment;
import com.payflow.entity.PaymentStatus;
import com.payflow.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository paymentRepository;
    private final MockBankGateway bankGateway;
    private final PaymentService paymentService;

    public record ReconciliationSummary(int scanned, int settled, int failed, int unchanged) {}

    public ReconciliationService(
        PaymentRepository paymentRepository,
        MockBankGateway bankGateway,
        PaymentService paymentService
    ) {
        this.paymentRepository = paymentRepository;
        this.bankGateway = bankGateway;
        this.paymentService = paymentService;
    }

    /**
     * Periodic scheduled worker to detect and resolve stuck or orphaned transactions.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedRate = 60000, initialDelay = 15000)
    public void scheduledReconciliation() {
        runReconciliation();
    }

    @Transactional
    public ReconciliationSummary runReconciliation() {
        // Find payments stuck in PROCESSING state for more than 1 minute
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        List<Payment> stuckPayments = paymentRepository.findByStatusAndCreatedAtBefore(PaymentStatus.PROCESSING, cutoff);

        if (stuckPayments.isEmpty()) {
            return new ReconciliationSummary(0, 0, 0, 0);
        }

        log.info("Starting reconciliation cycle for {} stuck transactions", stuckPayments.size());

        int settledCount = 0;
        int failedCount = 0;
        int unchangedCount = 0;

        for (Payment payment : stuckPayments) {
            MockBankGateway.GatewayTransactionRecord bankRecord = bankGateway.queryStatus(payment.getPaymentReference());

            if ("SETTLED".equalsIgnoreCase(bankRecord.status())) {
                log.info("Reconciliation resolved: Payment {} was SETTLED at bank.", payment.getPaymentReference());
                paymentService.markSettled(payment.getId(), bankRecord.authCode());
                settledCount++;
            } else if ("NOT_FOUND".equalsIgnoreCase(bankRecord.status()) || "FAILED".equalsIgnoreCase(bankRecord.status())) {
                log.warn("Reconciliation resolved: Payment {} not found or failed at bank. Marking FAILED and refunding balance.", payment.getPaymentReference());
                paymentService.markFailed(payment.getId(), "Reconciliation: Transaction failed or not found at external gateway.");
                failedCount++;
            } else {
                unchangedCount++;
            }
        }

        log.info("Reconciliation cycle finished: {} settled, {} refunded, {} unchanged", settledCount, failedCount, unchangedCount);
        return new ReconciliationSummary(stuckPayments.size(), settledCount, failedCount, unchangedCount);
    }
}
