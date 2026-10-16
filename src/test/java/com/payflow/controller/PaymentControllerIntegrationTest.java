package com.payflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.PaymentRequest;
import com.payflow.dto.RegisterRequest;
import com.payflow.entity.Account;
import com.payflow.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private com.payflow.repository.UserRepository userRepository;

    @Test
    @DisplayName("End-to-end payment creation with Idempotency-Key retry guarantees exactly-once balance deduction")
    void testPaymentIdempotencyFlow() throws Exception {
        // 1. Register User 1 (Sender - auto-provisioned with $1000)
        RegisterRequest senderReq = new RegisterRequest("sender_idemp@test.com", "password123", "Sender User", "USD");
        MvcResult senderResult = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(senderReq)))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode senderNode = objectMapper.readTree(senderResult.getResponse().getContentAsString());
        String senderToken = senderNode.get("token").asText();

        // 2. Register User 2 (Recipient - auto-provisioned with $1000)
        RegisterRequest recipientReq = new RegisterRequest("recipient_idemp@test.com", "password123", "Recipient User", "USD");
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recipientReq)))
            .andExpect(status().isCreated());

        // Find recipient account number
        var recipientUser = userRepository.findByEmail("recipient_idemp@test.com").orElseThrow();
        Account recipientAccount = accountRepository.findByUserId(recipientUser.getId()).get(0);

        // 3. Initiate Payment with Idempotency-Key
        String idempotencyKey = "uuid-mobile-retry-key-777";
        PaymentRequest paymentRequest = new PaymentRequest(
            null, // auto-select primary account
            recipientAccount.getAccountNumber(),
            new BigDecimal("250.00"),
            "USD"
        );

        MvcResult firstAttempt = mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", "Bearer " + senderToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(paymentRequest)))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstResponse = objectMapper.readTree(firstAttempt.getResponse().getContentAsString());
        String paymentReference = firstResponse.get("paymentReference").asText();
        assertThat(paymentReference).isNotNull();

        // 4. Client network retry: Second request with IDENTICAL Idempotency-Key
        MvcResult secondAttempt = mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", "Bearer " + senderToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(paymentRequest)))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode secondResponse = objectMapper.readTree(secondAttempt.getResponse().getContentAsString());
        String secondPaymentRef = secondResponse.get("paymentReference").asText();

        // Verifications:
        // Must return identical payment reference
        assertThat(secondPaymentRef).isEqualTo(paymentReference);

        // Verify balance was only deducted ONCE ($1000 - $250 = $750)
        var senderUser = userRepository.findByEmail("sender_idemp@test.com").orElseThrow();
        Account senderAccAfter = accountRepository.findByUserId(senderUser.getId()).get(0);

        assertThat(senderAccAfter.getBalance()).isEqualByComparingTo("750.00");
    }
}
