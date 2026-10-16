package com.payflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.PaymentRequest;
import com.payflow.dto.PaymentResponse;
import com.payflow.entity.*;
import com.payflow.exception.InsufficientFundsException;
import com.payflow.repository.AccountRepository;
import com.payflow.repository.OutboxRepository;
import com.payflow.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private PaymentService paymentService;

    private User senderUser;
    private User recipientUser;
    private Account senderAccount;
    private Account recipientAccount;

    @BeforeEach
    void setUp() {
        senderUser = new User("alice@test.com", "pass", "Alice Smith", Role.ROLE_CUSTOMER);
        senderUser.setId(UUID.randomUUID());

        recipientUser = new User("bob@test.com", "pass", "Bob Jones", Role.ROLE_CUSTOMER);
        recipientUser.setId(UUID.randomUUID());

        senderAccount = new Account("ACC-SENDER-001", new BigDecimal("500.00"), "USD", senderUser);
        senderAccount.setId(UUID.randomUUID());

        recipientAccount = new Account("ACC-RECIPIENT-002", new BigDecimal("100.00"), "USD", recipientUser);
        recipientAccount.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should successfully transfer funds, save outbox event, and record idempotency")
    void initiatePayment_Success() {
        String idempotencyKey = "key-test-123";
        PaymentRequest request = new PaymentRequest(
            senderAccount.getId(),
            recipientAccount.getAccountNumber(),
            new BigDecimal("150.00"),
            "USD"
        );

        when(idempotencyService.reserveOrGet(idempotencyKey, senderUser.getId())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(senderAccount.getId())).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByAccountNumberForUpdate(recipientAccount.getAccountNumber())).thenReturn(Optional.of(recipientAccount));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        PaymentResponse response = paymentService.initiatePayment(request, idempotencyKey, senderUser.getId());

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo("150.00");
        assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);

        // Verify balances adjusted
        assertThat(senderAccount.getBalance()).isEqualByComparingTo("350.00");
        assertThat(recipientAccount.getBalance()).isEqualByComparingTo("250.00");

        // Verify outbox event saved for Kafka publishing
        verify(outboxRepository, times(1)).save(any(OutboxEvent.class));
        verify(idempotencyService, times(1)).recordSuccess(eq(idempotencyKey), eq(senderUser.getId()), any(), eq(201));
    }

    @Test
    @DisplayName("Should return cached response on idempotent duplicate request without deducting balance")
    void initiatePayment_IdempotentHit() {
        String idempotencyKey = "key-cached-999";
        PaymentRequest request = new PaymentRequest(
            senderAccount.getId(),
            recipientAccount.getAccountNumber(),
            new BigDecimal("150.00"),
            "USD"
        );

        PaymentResponse cachedResponse = new PaymentResponse(
            UUID.randomUUID(), "PAY-REF-CACHED", senderAccount.getAccountNumber(),
            recipientAccount.getAccountNumber(), new BigDecimal("150.00"), "USD",
            PaymentStatus.PROCESSING, idempotencyKey, null, null, null
        );

        when(idempotencyService.reserveOrGet(idempotencyKey, senderUser.getId())).thenReturn(Optional.of(cachedResponse));

        PaymentResponse response = paymentService.initiatePayment(request, idempotencyKey, senderUser.getId());

        assertThat(response.paymentReference()).isEqualTo("PAY-REF-CACHED");
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when transfer amount exceeds balance")
    void initiatePayment_InsufficientFunds() {
        String idempotencyKey = "key-overdraw";
        PaymentRequest request = new PaymentRequest(
            senderAccount.getId(),
            recipientAccount.getAccountNumber(),
            new BigDecimal("1000.00"), // Exceeds $500 balance
            "USD"
        );

        when(idempotencyService.reserveOrGet(idempotencyKey, senderUser.getId())).thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(senderAccount.getId())).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findByAccountNumberForUpdate(recipientAccount.getAccountNumber())).thenReturn(Optional.of(recipientAccount));

        assertThatThrownBy(() -> paymentService.initiatePayment(request, idempotencyKey, senderUser.getId()))
            .isInstanceOf(InsufficientFundsException.class)
            .hasMessageContaining("Insufficient funds");

        // Verify balance was not modified
        assertThat(senderAccount.getBalance()).isEqualByComparingTo("500.00");
        verify(idempotencyService, times(1)).recordFailure(idempotencyKey, senderUser.getId());
    }
}
