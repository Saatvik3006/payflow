package com.payflow.service;

import com.payflow.config.JwtService;
import com.payflow.dto.AuthResponse;
import com.payflow.dto.LoginRequest;
import com.payflow.dto.RegisterRequest;
import com.payflow.entity.Account;
import com.payflow.entity.Role;
import com.payflow.entity.User;
import com.payflow.exception.DuplicateRequestException;
import com.payflow.repository.AccountRepository;
import com.payflow.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
        UserRepository userRepository,
        AccountRepository accountRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateRequestException("User with email " + request.email() + " already exists");
        }

        User user = new User(
            request.email(),
            passwordEncoder.encode(request.password()),
            request.fullName(),
            Role.ROLE_CUSTOMER
        );
        User savedUser = userRepository.save(user);

        // Auto-provision initial account with starting balance ($1000.00) for testing
        String currency = request.initialCurrency() != null && !request.initialCurrency().isBlank()
            ? request.initialCurrency().toUpperCase()
            : "USD";
        String accountNumber = generateAccountNumber();
        Account account = new Account(accountNumber, new BigDecimal("1000.00"), currency, savedUser);
        accountRepository.save(account);

        String token = jwtService.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole().name());
        return AuthResponse.of(token, savedUser.getId(), savedUser.getEmail(), savedUser.getFullName(), savedUser.getRole().name());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return AuthResponse.of(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole().name());
    }

    private String generateAccountNumber() {
        long num = 1000000000L + (long)(random.nextDouble() * 9000000000L);
        return "ACC-" + num;
    }
}
