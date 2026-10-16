package com.payflow.controller;

import com.payflow.config.JwtAuthenticationFilter.UserPrincipal;
import com.payflow.dto.AccountResponse;
import com.payflow.dto.DepositRequest;
import com.payflow.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Account balances, details, and fund deposits")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user's accounts", description = "Retrieves all accounts belonging to the authenticated user")
    public ResponseEntity<List<AccountResponse>> getMyAccounts(@AuthenticationPrincipal UserPrincipal principal) {
        List<AccountResponse> accounts = accountService.getAccountsForUser(principal.id());
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details by ID", description = "Retrieves account balance and metadata")
    public ResponseEntity<AccountResponse> getAccount(
        @PathVariable UUID accountId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        AccountResponse account = accountService.getAccount(accountId, principal.id());
        return ResponseEntity.ok(account);
    }

    @PostMapping("/{accountId}/deposit")
    @Operation(summary = "Deposit funds to account", description = "Atomically deposits funds using row-level locking")
    public ResponseEntity<AccountResponse> deposit(
        @PathVariable UUID accountId,
        @Valid @RequestBody DepositRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        AccountResponse response = accountService.deposit(accountId, request, principal.id());
        return ResponseEntity.ok(response);
    }
}
