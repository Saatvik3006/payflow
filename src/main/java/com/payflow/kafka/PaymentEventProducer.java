package com.payflow.kafka;

import com.payflow.dto.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;

@Component
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.payment-events:payment.events}")
    private String paymentEventsTopic;

    @Value("${app.kafka.topics.notification-events:notification.events}")
    private String notificationEventsTopic;

    public PaymentEventProducer(@Autowired(required = false) KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendPaymentEvent(PaymentEvent event) {
        log.info("Publishing PaymentEvent to topic '{}': reference={}", paymentEventsTopic, event.paymentReference());
        if (kafkaTemplate != null) {
            kafkaTemplate.send(paymentEventsTopic, event.paymentReference(), event);
        } else {
            log.debug("KafkaTemplate is null (Kafka disabled in test profile), skipped live publish.");
        }
    }

    public void sendNotificationEvent(String paymentReference, String message) {
        log.info("Publishing NotificationEvent to topic '{}': reference={}, msg={}", notificationEventsTopic, paymentReference, message);
        if (kafkaTemplate != null) {
            kafkaTemplate.send(notificationEventsTopic, paymentReference, message);
        } else {
            log.debug("KafkaTemplate is null (Kafka disabled in test profile), skipped live publish.");
        }
    }
}
