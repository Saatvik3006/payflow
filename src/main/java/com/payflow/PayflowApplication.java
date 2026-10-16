package com.payflow;

import com.payflow.entity.Role;
import com.payflow.entity.User;
import com.payflow.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@EnableScheduling
public class PayflowApplication {

    private static final Logger log = LoggerFactory.getLogger(PayflowApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(PayflowApplication.class, args);
    }

    @Bean
    public CommandLineRunner initAdminUser(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            String adminEmail = "admin@payflow.internal";
            if (!userRepository.existsByEmail(adminEmail)) {
                User admin = new User(
                    adminEmail,
                    passwordEncoder.encode("admin123456"),
                    "System Administrator",
                    Role.ROLE_ADMIN
                );
                userRepository.save(admin);
                log.info("Initialized default administrator: {} (password: admin123456)", adminEmail);
            }
        };
    }
}
