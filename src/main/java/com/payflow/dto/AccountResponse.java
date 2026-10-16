package com.payflow.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    UUID id,
    String accountNumber,
    BigDecimal balance,
    String currency,
    Instant createdAt
) {}
