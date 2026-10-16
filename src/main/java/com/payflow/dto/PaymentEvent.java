package com.payflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(
    String eventId,
    String eventType,
    UUID paymentId,
    String paymentReference,
    String sourceAccountNumber,
    String destinationAccountNumber,
    BigDecimal amount,
    String currency,
    Instant timestamp
) {
    public static PaymentEvent initiated(UUID paymentId, String paymentRef, String srcAcc, String dstAcc, BigDecimal amount, String currency) {
        return new PaymentEvent(
            UUID.randomUUID().toString(),
            "PAYMENT_INITIATED",
            paymentId,
            paymentRef,
            srcAcc,
            dstAcc,
            amount,
            currency,
            Instant.now()
        );
    }
}
