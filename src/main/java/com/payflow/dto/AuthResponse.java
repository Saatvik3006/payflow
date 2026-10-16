package com.payflow.dto;

import java.util.UUID;

public record AuthResponse(
    String token,
    String tokenType,
    UUID userId,
    String email,
    String fullName,
    String role
) {
    public static AuthResponse of(String token, UUID userId, String email, String fullName, String role) {
        return new AuthResponse(token, "Bearer", userId, email, fullName, role);
    }
}
