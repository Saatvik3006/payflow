package com.payflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.PaymentEvent;
import com.payflow.entity.OutboxEvent;
import com.payflow.entity.OutboxStatus;
import com.payflow.kafka.PaymentEventProducer;
import com.payflow.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final PaymentEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(
        OutboxRepository outboxRepository,
        PaymentEventProducer eventProducer,
        ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository.findByStatusOrderByCreatedAtAsc(
            OutboxStatus.PENDING,
            PageRequest.of(0, 50)
        );

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to publish", pending.size());

        for (OutboxEvent event : pending) {
            try {
                PaymentEvent paymentEvent = objectMapper.readValue(event.getPayload(), PaymentEvent.class);
                eventProducer.sendPaymentEvent(paymentEvent);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);
                log.info("Outbox event published successfully: aggregateId={}", event.getAggregateId());
            } catch (Exception ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= 5) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event marked FAILED after {} retries: aggregateId={}", event.getRetryCount(), event.getAggregateId());
                } else {
                    log.warn("Failed to publish outbox event: aggregateId={}, retry={}", event.getAggregateId(), event.getRetryCount());
                }
                outboxRepository.save(event);
            }
        }
    }
}
