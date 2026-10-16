package com.payflow.kafka;

import com.payflow.dto.PaymentEvent;
import com.payflow.service.MockBankGateway;
import com.payflow.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final MockBankGateway mockBankGateway;
    private final PaymentService paymentService;
    private final PaymentEventProducer eventProducer;

    public PaymentEventConsumer(
        MockBankGateway mockBankGateway,
        PaymentService paymentService,
        PaymentEventProducer eventProducer
    ) {
        this.mockBankGateway = mockBankGateway;
        this.paymentService = paymentService;
        this.eventProducer = eventProducer;
    }

    @KafkaListener(
        topics = "${app.kafka.topics.payment-events:payment.events}",
        groupId = "${spring.kafka.consumer.group-id:payflow-payment-group}",
        containerFactory = "kafkaListenerContainerFactory",
        autoStartup = "${app.kafka.enabled:true}"
    )
    public void handlePaymentInitiated(PaymentEvent event) {
        log.info("Processing async payment event: reference={}, amount={} {}",
            event.paymentReference(), event.amount(), event.currency());

        // 1. Authorize & Settle against mock bank clearing network
        String authCode = mockBankGateway.authorizeAndSettle(
            event.paymentReference(),
            event.amount(),
            event.currency()
        );

        // 2. Transition state to SETTLED
        paymentService.markSettled(event.paymentId(), authCode);

        // 3. Emit async notification event
        eventProducer.sendNotificationEvent(
            event.paymentReference(),
            "Payment " + event.paymentReference() + " for " + event.amount() + " " + event.currency() + " successfully settled."
        );
    }
}
