package com.payflow.dto;

import com.payflow.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    String paymentReference,
    String sourceAccountNumber,
    String destinationAccountNumber,
    BigDecimal amount,
    String currency,
    PaymentStatus status,
    String idempotencyKey,
    String failureReason,
    Instant createdAt,
    Instant updatedAt
) {}
