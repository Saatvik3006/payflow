package com.payflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.PaymentEvent;
import com.payflow.dto.PaymentRequest;
import com.payflow.dto.PaymentResponse;
import com.payflow.entity.*;
import com.payflow.exception.InsufficientFundsException;
import com.payflow.exception.PaymentProcessingException;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.AccountRepository;
import com.payflow.repository.OutboxRepository;
import com.payflow.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public PaymentService(
        AccountRepository accountRepository,
        PaymentRepository paymentRepository,
        OutboxRepository outboxRepository,
        IdempotencyService idempotencyService,
        ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes a payment transfer with idempotency, pessimistic row locking, and transactional outbox.
     */
    @Transactional
    public PaymentResponse initiatePayment(PaymentRequest request, String idempotencyKey, UUID userId) {
        // Step 1: Enforce Idempotency
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentResponse> cached = idempotencyService.reserveOrGet(idempotencyKey, userId);
            if (cached.isPresent()) {
                log.info("Idempotent hit for key: {}. Returning cached response.", idempotencyKey);
                return cached.get();
            }
        }

        try {
            // Step 2: Resolve Source Account
            Account sourceAccount;
            if (request.sourceAccountId() != null) {
                sourceAccount = accountRepository.findByIdForUpdate(request.sourceAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Source account not found"));
            } else {
                List<Account> userAccounts = accountRepository.findByUserId(userId);
                if (userAccounts.isEmpty()) {
                    throw new ResourceNotFoundException("No account found for user");
                }
                sourceAccount = accountRepository.findByIdForUpdate(userAccounts.get(0).getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Source account not found"));
            }

            if (!sourceAccount.getUser().getId().equals(userId)) {
                throw new AccessDeniedException("Unauthorized access to source account");
            }

            // Step 3: Resolve Destination Account
            Account destinationAccount = accountRepository.findByAccountNumberForUpdate(request.destinationAccountNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Destination account not found: " + request.destinationAccountNumber()));

            if (sourceAccount.getId().equals(destinationAccount.getId())) {
                throw new PaymentProcessingException("Source and destination accounts cannot be the same");
            }

            // Step 4: Validate Balance under Pessimistic Write Lock
            BigDecimal transferAmount = request.amount();
            if (sourceAccount.getBalance().compareTo(transferAmount) < 0) {
                throw new InsufficientFundsException(
                    "Insufficient funds in account " + sourceAccount.getAccountNumber() +
                    ". Available: " + sourceAccount.getBalance() + ", Requested: " + transferAmount
                );
            }

            // Step 5: Deduct and Credit Balance (Double-entry principle)
            sourceAccount.setBalance(sourceAccount.getBalance().subtract(transferAmount));
            destinationAccount.setBalance(destinationAccount.getBalance().add(transferAmount));

            accountRepository.save(sourceAccount);
            accountRepository.save(destinationAccount);

            // Step 6: Create Payment Record
            String paymentRef = "PAY-" + System.currentTimeMillis() + "-" + (1000 + random.nextInt(9000));
            Payment payment = new Payment(
                paymentRef,
                sourceAccount,
                destinationAccount,
                transferAmount,
                sourceAccount.getCurrency(),
                idempotencyKey
            );
            payment.setStatus(PaymentStatus.PROCESSING);
            Payment savedPayment = paymentRepository.save(payment);

            // Step 7: Create Transactional Outbox Event
            PaymentEvent event = PaymentEvent.initiated(
                savedPayment.getId(),
                savedPayment.getPaymentReference(),
                sourceAccount.getAccountNumber(),
                destinationAccount.getAccountNumber(),
                transferAmount,
                sourceAccount.getCurrency()
            );

            try {
                String payloadJson = objectMapper.writeValueAsString(event);
                OutboxEvent outboxEvent = new OutboxEvent(
                    "PAYMENT",
                    savedPayment.getPaymentReference(),
                    "PAYMENT_INITIATED",
                    payloadJson
                );
                outboxRepository.save(outboxEvent);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize outbox event for payment {}", paymentRef, e);
                throw new PaymentProcessingException("Failed to serialize payment event", e);
            }

            PaymentResponse response = mapToResponse(savedPayment);

            // Step 8: Cache successful response under idempotency key
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.recordSuccess(idempotencyKey, userId, response, 201);
            }

            return response;

        } catch (Exception ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.recordFailure(idempotencyKey, userId);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByReference(String reference, UUID userId) {
        Payment payment = paymentRepository.findByPaymentReference(reference)
            .orElseThrow(() -> new ResourceNotFoundException("Payment not found with reference: " + reference));

        boolean isParticipant = payment.getSourceAccount().getUser().getId().equals(userId) ||
                                payment.getDestinationAccount().getUser().getId().equals(userId);

        if (!isParticipant) {
            throw new AccessDeniedException("Access denied to payment reference: " + reference);
        }

        return mapToResponse(payment);
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> getPaymentHistory(UUID userId, Pageable pageable) {
        return paymentRepository.findByUserId(userId, pageable)
            .map(this::mapToResponse);
    }

    @Transactional
    public void markSettled(UUID paymentId, String authCode) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            p.setStatus(PaymentStatus.SETTLED);
            p.setFailureReason(null);
            paymentRepository.save(p);
            log.info("Payment {} successfully marked as SETTLED with authCode {}", p.getPaymentReference(), authCode);
        });
    }

    @Transactional
    public void markFailed(UUID paymentId, String reason) {
        paymentRepository.findById(paymentId).ifPresent(p -> {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(reason);

            // Refund/revert balance deduction
            Account source = p.getSourceAccount();
            Account dest = p.getDestinationAccount();
            source.setBalance(source.getBalance().add(p.getAmount()));
            dest.setBalance(dest.getBalance().subtract(p.getAmount()));

            accountRepository.save(source);
            accountRepository.save(dest);
            paymentRepository.save(p);

            log.warn("Payment {} marked as FAILED. Balance refunded to account {}", p.getPaymentReference(), source.getAccountNumber());
        });
    }

    private PaymentResponse mapToResponse(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getPaymentReference(),
            payment.getSourceAccount().getAccountNumber(),
            payment.getDestinationAccount().getAccountNumber(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getStatus(),
            payment.getIdempotencyKey(),
            payment.getFailureReason(),
            payment.getCreatedAt(),
            payment.getUpdatedAt()
        );
    }
}
