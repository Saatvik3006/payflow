package com.payflow.controller;

import com.payflow.config.JwtAuthenticationFilter.UserPrincipal;
import com.payflow.dto.PaymentRequest;
import com.payflow.dto.PaymentResponse;
import com.payflow.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Idempotent payment creation, status querying, and transaction history")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(
        summary = "Initiate a payment transfer",
        description = "Processes a payment between accounts with pessimistic concurrency locking, idempotency guarantees, and transactional outbox event publishing."
    )
    public ResponseEntity<PaymentResponse> initiatePayment(
        @Parameter(description = "Unique client-generated idempotency key (UUID) to prevent double charges on network retry")
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody PaymentRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        PaymentResponse response = paymentService.initiatePayment(request, idempotencyKey, principal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentReference}")
    @Operation(summary = "Get payment details by reference", description = "Queries status and metadata for a given payment reference")
    public ResponseEntity<PaymentResponse> getPayment(
        @PathVariable String paymentReference,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        PaymentResponse response = paymentService.getPaymentByReference(paymentReference, principal.id());
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "Query payment history", description = "Retrieves paginated transaction history for the authenticated user")
    public ResponseEntity<Page<PaymentResponse>> getHistory(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        Page<PaymentResponse> history = paymentService.getPaymentHistory(principal.id(), pageable);
        return ResponseEntity.ok(history);
    }
}
