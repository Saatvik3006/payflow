package com.payflow.service;

import com.payflow.dto.AccountResponse;
import com.payflow.dto.DepositRequest;
import com.payflow.entity.Account;
import com.payflow.exception.ResourceNotFoundException;
import com.payflow.repository.AccountRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsForUser(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId, UUID userId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + accountId));

        if (!account.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Access denied to account " + accountId);
        }

        return mapToResponse(account);
    }

    @Transactional
    public AccountResponse deposit(UUID accountId, DepositRequest request, UUID userId) {
        // Pessimistic lock for atomic balance adjustment
        Account account = accountRepository.findByIdForUpdate(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + accountId));

        if (!account.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Access denied to account " + accountId);
        }

        account.setBalance(account.getBalance().add(request.amount()));
        Account saved = accountRepository.save(account);
        return mapToResponse(saved);
    }

    private AccountResponse mapToResponse(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getAccountNumber(),
            account.getBalance(),
            account.getCurrency(),
            account.getCreatedAt()
        );
    }
}
