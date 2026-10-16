package com.payflow.kafka;

import com.payflow.dto.PaymentEvent;
import com.payflow.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class DeadLetterQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueConsumer.class);

    private final PaymentService paymentService;
    private final List<DlqRecord> dlqAuditLog = new CopyOnWriteArrayList<>();

    public record DlqRecord(String paymentReference, String reason, Instant timestamp) {}

    public DeadLetterQueueConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(
        topics = "${app.kafka.topics.payment-dlt:payment.events.DLT}",
        groupId = "payflow-dlq-audit-group",
        containerFactory = "kafkaListenerContainerFactory",
        autoStartup = "${app.kafka.enabled:true}"
    )
    public void handleDeadLetterMessage(PaymentEvent failedEvent) {
        log.error("CRITICAL: Message received in Dead Letter Topic (DLT): reference={}, amount={}",
            failedEvent.paymentReference(), failedEvent.amount());

        // Revert ledger balances and mark payment as FAILED
        paymentService.markFailed(failedEvent.paymentId(), "Retries exhausted. Routed to Dead Letter Topic.");

        dlqAuditLog.add(new DlqRecord(
            failedEvent.paymentReference(),
            "Exceeded maximum consumer retries during bank settlement",
            Instant.now()
        ));
    }

    public List<DlqRecord> getDlqAuditLog() {
        return List.copyOf(dlqAuditLog);
    }
}
