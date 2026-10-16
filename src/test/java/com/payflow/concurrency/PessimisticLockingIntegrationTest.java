package com.payflow.concurrency;

import com.payflow.dto.PaymentRequest;
import com.payflow.entity.Account;
import com.payflow.entity.Role;
import com.payflow.entity.User;
import com.payflow.exception.InsufficientFundsException;
import com.payflow.repository.AccountRepository;
import com.payflow.repository.UserRepository;
import com.payflow.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PessimisticLockingIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private User senderUser;
    private Account senderAccount;
    private Account recipientAccount;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        userRepository.deleteAll();

        senderUser = userRepository.save(new User("concurrent_sender@test.com", "pass", "Sender", Role.ROLE_CUSTOMER));
        User recipientUser = userRepository.save(new User("concurrent_recipient@test.com", "pass", "Recipient", Role.ROLE_CUSTOMER));

        // Initial balance: $500.00
        senderAccount = accountRepository.save(new Account("ACC-CONC-SENDER", new BigDecimal("500.00"), "USD", senderUser));
        recipientAccount = accountRepository.save(new Account("ACC-CONC-RECIPIENT", new BigDecimal("0.00"), "USD", recipientUser));
    }

    @Test
    @DisplayName("Pessimistic Locking under Race Condition: 10 concurrent $100 requests against $500 account must yield exactly 5 successes and zero negative balance")
    void testConcurrentTransfersWithPessimisticLocking() throws InterruptedException {
        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("100.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // All threads wait here until released simultaneously
                    String uniqueIdempotencyKey = "key-conc-" + UUID.randomUUID() + "-" + index;
                    PaymentRequest request = new PaymentRequest(
                        senderAccount.getId(),
                        recipientAccount.getAccountNumber(),
                        transferAmount,
                        "USD"
                    );
                    paymentService.initiatePayment(request, uniqueIdempotencyKey, senderUser.getId());
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException ex) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(); // Wait for all threads to be ready
        startLatch.countDown(); // Fire all 10 threads simultaneously!
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verification of concurrency correctness
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(insufficientFundsCount.get()).isEqualTo(5);

        Account finalSenderAccount = accountRepository.findById(senderAccount.getId()).orElseThrow();
        Account finalRecipientAccount = accountRepository.findById(recipientAccount.getId()).orElseThrow();

        // Exact balance verification: 500 - (5 * 100) = 0.00
        assertThat(finalSenderAccount.getBalance()).isEqualByComparingTo("0.00");
        assertThat(finalRecipientAccount.getBalance()).isEqualByComparingTo("500.00");
    }
}
