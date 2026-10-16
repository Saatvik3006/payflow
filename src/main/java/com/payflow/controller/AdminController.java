package com.payflow.controller;

import com.payflow.entity.OutboxStatus;
import com.payflow.kafka.DeadLetterQueueConsumer;
import com.payflow.kafka.DeadLetterQueueConsumer.DlqRecord;
import com.payflow.repository.OutboxRepository;
import com.payflow.service.ReconciliationService;
import com.payflow.service.ReconciliationService.ReconciliationSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration", description = "Operator controls for DLQ inspection, outbox status, and reconciliation")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final DeadLetterQueueConsumer dlqConsumer;
    private final OutboxRepository outboxRepository;

    public AdminController(
        ReconciliationService reconciliationService,
        DeadLetterQueueConsumer dlqConsumer,
        OutboxRepository outboxRepository
    ) {
        this.reconciliationService = reconciliationService;
        this.dlqConsumer = dlqConsumer;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping("/reconciliation/run")
    @Operation(summary = "Trigger reconciliation cycle", description = "Manually triggers background reconciliation to reconcile stuck payments with mock bank gateway")
    public ResponseEntity<ReconciliationSummary> triggerReconciliation() {
        ReconciliationSummary summary = reconciliationService.runReconciliation();
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/dlq/events")
    @Operation(summary = "Inspect Dead Letter Queue (DLQ) records", description = "Retrieves recent failed payments routed to DLT after retry exhaustion")
    public ResponseEntity<List<DlqRecord>> getDlqEvents() {
        return ResponseEntity.ok(dlqConsumer.getDlqAuditLog());
    }

    @GetMapping("/outbox/status")
    @Operation(summary = "Inspect Outbox table health", description = "Returns counts of PENDING, PUBLISHED, and FAILED outbox events")
    public ResponseEntity<Map<String, Long>> getOutboxStatus() {
        Map<String, Long> statusMap = Map.of(
            "PENDING", outboxRepository.countByStatus(OutboxStatus.PENDING),
            "PUBLISHED", outboxRepository.countByStatus(OutboxStatus.PUBLISHED),
            "FAILED", outboxRepository.countByStatus(OutboxStatus.FAILED)
        );
        return ResponseEntity.ok(statusMap);
    }
}
