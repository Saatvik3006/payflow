package com.payflow.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.payment-events:payment.events}")
    private String paymentEventsTopic;

    @Value("${app.kafka.topics.payment-dlt:payment.events.DLT}")
    private String paymentDltTopic;

    @Value("${app.kafka.topics.notification-events:notification.events}")
    private String notificationEventsTopic;

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(paymentEventsTopic)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public NewTopic paymentDltTopic() {
        return TopicBuilder.name(paymentDltTopic)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
    public NewTopic notificationEventsTopic() {
        return TopicBuilder.name(notificationEventsTopic)
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    public CommonErrorHandler errorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        // Recoverer publishes failed records to the Dead Letter Topic (.DLT)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);

        // Retry 2 times with 1-second fixed delay before routing to DLQ
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
        ConsumerFactory<String, Object> consumerFactory,
        CommonErrorHandler errorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
